//! Owned, offline Git fixtures. Never accept a caller's repository as a target.
use history::{ContentId, Coverage, FileInput, FileKind, HistoryStore, SnapshotInput, Workspace};
use protocol::{MutationKey, WorkspaceId};
use std::{
    collections::BTreeMap,
    fs,
    path::{Component, Path, PathBuf},
    process::{Command, Output},
};

#[cfg(unix)]
use std::os::unix::{ffi::OsStrExt, fs::PermissionsExt};

pub struct Repository {
    pub temp: tempfile::TempDir,
    pub root: PathBuf,
    pub data: PathBuf,
}

impl Repository {
    pub fn new() -> Self {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("repository with spaces");
        let data = temp.path().join("history");
        fs::create_dir(&root).unwrap();
        fs::create_dir(&data).unwrap();
        let fixture = Self { temp, root, data };
        fixture.git(&["init", "--initial-branch=main"]);
        fixture.write("tracked.txt", b"base\n");
        fixture.git(&["add", "--", "tracked.txt"]);
        fixture.git(&["commit", "-m", "fixture baseline"]);
        fixture
    }

    pub fn git_output(&self, args: &[&str]) -> Output {
        Command::new("git")
            .args([
                "-c",
                if cfg!(windows) {
                    "core.hooksPath=NUL"
                } else {
                    "core.hooksPath=/dev/null"
                },
                "-c",
                "commit.gpgSign=false",
                "-c",
                "core.autocrlf=false",
                "-c",
                "init.templateDir=",
            ])
            .args(args)
            .current_dir(&self.root)
            .env_clear()
            .env("PATH", std::env::var_os("PATH").unwrap_or_default())
            .env("HOME", self.temp.path())
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env(
                "GIT_CONFIG_GLOBAL",
                if cfg!(windows) { "NUL" } else { "/dev/null" },
            )
            .envs(
                ["SYSTEMROOT", "WINDIR", "TEMP", "TMP"]
                    .into_iter()
                    .filter_map(|name| std::env::var_os(name).map(|value| (name, value))),
            )
            .env("GIT_TERMINAL_PROMPT", "0")
            .env("GIT_AUTHOR_NAME", "Flow Fixture")
            .env("GIT_AUTHOR_EMAIL", "fixture@example.invalid")
            .env("GIT_COMMITTER_NAME", "Flow Fixture")
            .env("GIT_COMMITTER_EMAIL", "fixture@example.invalid")
            .env("GIT_AUTHOR_DATE", "2000-01-01T00:00:00Z")
            .env("GIT_COMMITTER_DATE", "2000-01-01T00:00:00Z")
            .env("LC_ALL", "C")
            .output()
            .unwrap()
    }

    pub fn git(&self, args: &[&str]) -> Vec<u8> {
        let output = self.git_output(args);
        assert!(output.status.success(), "fixture Git failed: {:?}", args);
        output.stdout
    }

    pub fn write(&self, relative: &str, bytes: &[u8]) {
        let path = Path::new(relative);
        assert!(!path.as_os_str().is_empty());
        let mut target = self.root.clone();
        for component in path.components() {
            let Component::Normal(name) = component else {
                panic!("unsafe fixture path")
            };
            assert_ne!(name, ".git");
            target.push(name);
            if let Ok(metadata) = fs::symlink_metadata(&target) {
                assert!(
                    !metadata.file_type().is_symlink(),
                    "fixture path follows a symlink"
                );
            }
        }
        fs::create_dir_all(target.parent().unwrap()).unwrap();
        fs::write(&target, bytes).unwrap();
        #[cfg(unix)]
        fs::set_permissions(&target, fs::Permissions::from_mode(0o644)).unwrap();
    }

    pub fn store(&self) -> HistoryStore {
        let store = HistoryStore::open(&self.root, &self.data).unwrap();
        store
            .register_workspace(
                key("workspace"),
                Workspace {
                    id: workspace_id(),
                    worktree_path: self.root.clone(),
                    git_common_dir: self.root.join(".git"),
                    base_commit: String::from_utf8(self.git(&["rev-parse", "HEAD"]))
                        .unwrap()
                        .trim()
                        .into(),
                    owner_token: "fixture-owner".into(),
                    lifecycle: "ready".into(),
                },
            )
            .unwrap();
        store
    }
}

pub fn key(value: &str) -> MutationKey {
    MutationKey::try_from(value.to_owned()).unwrap()
}
pub fn workspace_id() -> WorkspaceId {
    WorkspaceId::try_from("fixture-workspace".to_owned()).unwrap()
}

/// Deterministic IDs and capture times supplied by the harness, never the wall clock.
#[derive(Default)]
pub struct Sequence(pub u64);
impl Sequence {
    pub fn capture(&mut self, repository: &Repository, store: &HistoryStore) -> ContentId {
        self.0 += 1;
        let mut files = BTreeMap::new();
        scan(&repository.root, &repository.root, &mut files);
        let receipt = store
            .publish_snapshot(
                key(&format!("capture-{}", self.0)),
                SnapshotInput {
                    workspace_id: workspace_id(),
                    files,
                    coverage: Coverage {
                        complete: true,
                        excluded: vec![],
                    },
                    started_at_ms: self.0,
                    completed_at_ms: self.0,
                    provenance: "B05 explicit fixture capture; not a production recorder".into(),
                },
            )
            .unwrap();
        serde_json::from_value(receipt.outcome.unwrap()["id"].clone()).unwrap()
    }
}

fn scan(root: &Path, directory: &Path, files: &mut BTreeMap<String, FileInput>) {
    for entry in fs::read_dir(directory).unwrap() {
        let path = entry.unwrap().path();
        if path == root.join(".git") {
            continue;
        }
        let metadata = fs::symlink_metadata(&path).unwrap();
        if metadata.is_dir() {
            scan(root, &path, files);
            continue;
        }
        let (kind, bytes) = if metadata.file_type().is_symlink() {
            #[cfg(windows)]
            panic!("Windows fixture does not create symlinks");
            #[cfg(unix)]
            (
                FileKind::Symlink,
                fs::read_link(&path)
                    .unwrap()
                    .as_os_str()
                    .as_bytes()
                    .to_vec(),
            )
        } else {
            assert!(metadata.is_file(), "unsupported fixture entry");
            (FileKind::Regular, fs::read(&path).unwrap())
        };
        files.insert(
            path.strip_prefix(root)
                .unwrap()
                .to_str()
                .unwrap()
                .replace('\\', "/"),
            FileInput {
                bytes,
                kind,
                #[cfg(unix)]
                mode: metadata.permissions().mode() & 0o777,
                #[cfg(windows)]
                mode: if metadata.permissions().readonly() {
                    0o444
                } else {
                    0o644
                },
            },
        );
    }
}

/// Independently authored expected bytes/types/modes. No hash/diff implementation
/// or filesystem scanner is shared with this oracle.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExpectedFile(pub Vec<u8>, pub u32, pub FileKind);
pub type Oracle = BTreeMap<String, ExpectedFile>;
pub fn regular(bytes: &[u8]) -> ExpectedFile {
    ExpectedFile(bytes.to_vec(), 0o644, FileKind::Regular)
}

pub fn verify(store: &HistoryStore, id: ContentId, expected: &Oracle) -> Result<(), String> {
    let snapshot = store.get_snapshot(id).map_err(|e| e.to_string())?;
    if !snapshot.manifest.coverage.complete || !snapshot.manifest.coverage.excluded.is_empty() {
        return Err("incomplete fixture capture".into());
    }
    let mut actual = BTreeMap::new();
    for (path, entry) in snapshot.manifest.files {
        let mut bytes = Vec::new();
        let mut offset = 0;
        loop {
            let chunk = store
                .read_content(entry.blob.clone(), offset, history::MAX_CHUNK_BYTES)
                .map_err(|e| e.to_string())?;
            bytes.extend(chunk.bytes);
            match chunk.next_offset {
                Some(next) => offset = next,
                None => break,
            }
        }
        if bytes.len() as u64 != entry.size {
            return Err(format!("size mismatch: {path}"));
        }
        actual.insert(path, ExpectedFile(bytes, entry.mode, entry.kind));
    }
    if actual.keys().ne(expected.keys()) {
        return Err("manifest paths differ from oracle".into());
    }
    for (path, wanted) in expected {
        let found = &actual[path];
        if found != wanted {
            return Err(format!(
                "manifest mismatch at {path:?}: bytes equal={}, mode={:o}/{:o}, kind={:?}/{:?}",
                found.0 == wanted.0,
                found.1,
                wanted.1,
                found.2,
                wanted.2
            ));
        }
    }
    Ok(())
}
