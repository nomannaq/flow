//! B07: owned clean-commit worktrees, not a security sandbox or an agent host.
//! All methods block. Async callers must use a bounded blocking worker and keep
//! the returned writer guard alive until every workspace writer has stopped.
pub(crate) mod io;
use history::{ContentId, Coverage, FileInput, FileKind, HistoryStore, SnapshotInput, Workspace};
use io::{Lease, directory, git, git_input, now, read, text, token, write};
pub use io::{Owner, Recovery};
use protocol::{MutationKey, WorkspaceId};
use serde::{Deserialize, Serialize};
#[cfg(unix)]
use std::os::unix::{ffi::OsStrExt, fs::PermissionsExt};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File},
    io::Read,
    path::{Component, Path, PathBuf},
    sync::Arc,
};

#[derive(Debug)]
pub enum Error {
    Io(std::io::Error),
    History(history::Error),
    Git(Option<i32>),
    Invalid(&'static str),
    Busy,
    StaleOwner(Owner),
    Unstable,
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Io(_) => "workspace I/O failed; preparation is retained",
            Self::History(_) => "workspace history publication failed; preparation is retained",
            Self::Git(_) => "Git operation failed; check repository access and inspect retained preparation",
            Self::Invalid(message) => message,
            Self::Busy => "another Flow writer owns this repository or workspace; close it first",
            Self::Unstable => "workspace changed during capture; no checkpoint was advanced",
            Self::StaleOwner(_) => "previous Flow owner exited without releasing its lease; explicitly acknowledge its instance token to recover",
        })
    }
}
impl std::error::Error for Error {}
impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}
impl From<history::Error> for Error {
    fn from(e: history::Error) -> Self {
        Self::History(e)
    }
}
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Repository {
    pub root: PathBuf,
    pub common_dir: PathBuf,
    pub head: String,
    pub git_version: String,
}
impl Repository {
    pub fn discover(project: &Path) -> Result<Self> {
        let project = project.canonicalize()?;
        let root = PathBuf::from(text(git(&project, ["rev-parse", "--show-toplevel"])?)?)
            .canonicalize()?;
        if root != project {
            return Err(Error::Invalid(
                "open the repository root, not a subdirectory",
            ));
        }
        let common_dir = PathBuf::from(text(git(
            &root,
            ["rev-parse", "--path-format=absolute", "--git-common-dir"],
        )?)?)
        .canonicalize()?;
        let head = text(git(&root, ["rev-parse", "--verify", "HEAD^{commit}"])?)?;
        let git_version = text(git(&root, ["--version"])?)?;
        let repo = Self {
            root,
            common_dir,
            head,
            git_version,
        };
        validate(&repo.root)?;
        Ok(repo)
    }
}

/// A caller must explicitly choose a resolved full commit ID. Local edits and
/// unsaved IDE buffers are never imported, saved or discarded by this API.
#[derive(Clone, Debug)]
pub enum BaselineChoice {
    CleanCommit(String),
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub enum Stage {
    Reserved,
    Created,
    Ready,
    Removed,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Preparation {
    pub workspace: Workspace,
    pub stage: Stage,
    pub baseline: Option<ContentId>,
    pub started_at_ms: u64,
}
pub struct WorktreeManager {
    repository: Repository,
    store: HistoryStore,
    directory: PathBuf,
    // Kept by each writer too; dropping the manager cannot admit another engine
    // while an owned worktree is still being written.
    repository_lease: Arc<Lease>,
}
pub struct PreparedWorktree {
    pub preparation: Preparation,
    _workspace: Lease,
    _writer: Lease,
    _repository: Arc<Lease>,
}
impl PreparedWorktree {
    pub(crate) fn owner_instance(&self) -> &str {
        &self._repository.owner.instance
    }
    /// Release OS handles but preserve stale ownership when shutdown is unknown.
    pub(crate) fn quarantine(&self) {
        self._workspace.preserve_owner();
        self._writer.preserve_owner();
        self._repository.preserve_owner();
    }
}
impl WorktreeManager {
    pub fn open(project: &Path, store: HistoryStore, recovery: Recovery) -> Result<Self> {
        let repository = Repository::discover(project)?;
        // Do not allow publication into a different project's history store.
        let expected = format!(
            "project-{}",
            ContentId::hash(repository.root.as_os_str().as_encoded_bytes()).as_str()
        );
        if store.directory().file_name().and_then(|s| s.to_str()) != Some(&expected) {
            return Err(Error::Invalid(
                "history store belongs to a different project",
            ));
        }
        let repository_lease = Arc::new(Lease::acquire(
            &repository.common_dir.join("flow-worktrees.lock"),
            &recovery,
        )?);
        let directory = store.directory().join("worktrees");
        io::directory(&directory)?;
        Ok(Self {
            repository,
            store,
            directory,
            repository_lease,
        })
    }
    pub fn repository(&self) -> &Repository {
        &self.repository
    }

    pub fn prepare(&self, choice: BaselineChoice) -> Result<PreparedWorktree> {
        self.prepare_with_recovery(choice, Recovery::RefuseStale)
    }
    pub fn prepare_with_recovery(
        &self,
        choice: BaselineChoice,
        recovery: Recovery,
    ) -> Result<PreparedWorktree> {
        if !self.store.storage_report()?.writable {
            return Err(Error::History(history::Error::ReadOnly));
        }
        self.prepare_inner(choice, recovery, |_| Ok(()))
    }
    fn prepare_inner(
        &self,
        choice: BaselineChoice,
        recovery: Recovery,
        checkpoint: impl Fn(Stage) -> Result<()>,
    ) -> Result<PreparedWorktree> {
        self.check_directory()?;
        let writer = Lease::acquire_for(
            &self.directory.join("writer.lock"),
            &recovery,
            self.repository_lease.owner.clone(),
        )?;
        validate(&self.repository.root)?;
        let BaselineChoice::CleanCommit(commit) = choice;
        if ![40, 64].contains(&commit.len()) || !commit.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(Error::Invalid(
                "select a full resolved commit ID for the clean baseline",
            ));
        }
        let resolved = text(git(
            &self.repository.root,
            ["rev-parse", "--verify", &format!("{commit}^{{commit}}")],
        )?)?;
        if resolved != commit {
            return Err(Error::Invalid("baseline must identify a commit directly"));
        }
        let tree = git(&self.repository.root, ["ls-tree", "-rz", &commit])?;
        if tree
            .split(|b| *b == 0)
            .any(|entry| entry.starts_with(b"160000 "))
        {
            return Err(Error::Invalid(
                "submodule baselines are unsupported; choose a repository without gitlinks",
            ));
        }
        let source = source_fingerprint(&self.repository.root)?;
        let owner = token()?;
        let id = WorkspaceId::try_from(format!("workspace-{owner}"))
            .map_err(|_| Error::Invalid("invalid workspace identity"))?;
        let record_dir = self.directory.join(id.as_str());
        // Never adopt an existing directory, even if an improbable ID collision occurs.
        fs::create_dir(&record_dir)?;
        directory(&record_dir)?;
        let workspace_lease = Lease::acquire_for(
            &record_dir.join("writer.lock"),
            &Recovery::RefuseStale,
            self.repository_lease.owner.clone(),
        )?;
        let path = record_dir.join("tree");
        let mut record = Preparation {
            workspace: Workspace {
                id,
                worktree_path: path.clone(),
                git_common_dir: self.repository.common_dir.clone(),
                base_commit: commit.clone(),
                owner_token: owner.clone(),
                lifecycle: "owned".into(),
            },
            stage: Stage::Reserved,
            baseline: None,
            started_at_ms: now()?,
        };
        self.save(&record)?;
        checkpoint(Stage::Reserved)?;
        git(
            &self.repository.root,
            [
                "worktree",
                "add",
                "--detach",
                "--no-checkout",
                "--lock",
                "--reason",
                &format!("flow-{owner}"),
                path_text(&path)?,
                &commit,
            ],
        )?;
        // Check attributes before checkout: a filter could execute arbitrary code.
        git(&path, ["read-tree", &commit])?;
        validate(&path)?;
        git(&path, ["reset", "--hard", &commit])?;
        self.verify_owned(&record)?;
        record.stage = Stage::Created;
        self.save(&record)?;
        checkpoint(Stage::Created)?;
        let files = capture(&path)?;
        if !git(
            &path,
            ["status", "--porcelain=v1", "-z", "--untracked-files=all"],
        )?
        .is_empty()
        {
            return Err(Error::Invalid(
                "new worktree changed during preparation; inspect retained files",
            ));
        }
        if source != source_fingerprint(&self.repository.root)? {
            return Err(Error::Invalid(
                "main project changed during preparation; retry after inspecting the retained worktree",
            ));
        }
        self.store
            .register_workspace(key(&owner, "register")?, record.workspace.clone())?;
        let receipt = self.store.publish_snapshot(
            key(&owner, "baseline")?,
            SnapshotInput {
                workspace_id: record.workspace.id.clone(),
                files,
                coverage: Coverage {
                    complete: true,
                    excluded: vec![],
                },
                started_at_ms: record.started_at_ms,
                completed_at_ms: now()?,
                provenance: format!(
                    "B07 clean commit {commit}; local edits and unsaved buffers excluded"
                ),
            },
        )?;
        record.baseline = Some(
            serde_json::from_value(
                receipt
                    .outcome
                    .ok_or(Error::Invalid("baseline receipt missing"))?["id"]
                    .clone(),
            )
            .map_err(|_| Error::Invalid("baseline receipt invalid"))?,
        );
        record.stage = Stage::Ready;
        self.save(&record)?;
        Ok(PreparedWorktree {
            preparation: record,
            _workspace: workspace_lease,
            _writer: writer,
            _repository: self.repository_lease.clone(),
        })
    }

    /// Records remain after a crash or normal guard drop; neither deletes files.
    pub fn preparations(&self) -> Result<Vec<Preparation>> {
        self.check_directory()?;
        let mut records = Vec::new();
        for entry in fs::read_dir(&self.directory)? {
            let entry = entry?;
            let name = entry.file_name();
            if !name.to_string_lossy().starts_with("workspace-") {
                continue;
            }
            let id = WorkspaceId::try_from(
                name.to_str()
                    .ok_or(Error::Invalid("invalid workspace path"))?
                    .to_owned(),
            )
            .map_err(|_| Error::Invalid("invalid workspace identity"))?;
            records.push(self.load(&id)?);
        }
        records.sort_by(|a, b| a.workspace.id.as_str().cmp(b.workspace.id.as_str()));
        Ok(records)
    }
    /// Explicit reacquisition, preserving all current workspace bytes. A crash
    /// requires acknowledging the old writer instance, never just its PID.
    pub fn acquire(&self, id: &WorkspaceId, recovery: Recovery) -> Result<PreparedWorktree> {
        self.check_directory()?;
        let writer = Lease::acquire_for(
            &self.directory.join("writer.lock"),
            &recovery,
            self.repository_lease.owner.clone(),
        )?;
        let record = self.load(id)?;
        let workspace_lease = Lease::acquire_for(
            &self.directory.join(id.as_str()).join("writer.lock"),
            &recovery,
            self.repository_lease.owner.clone(),
        )?;
        if record.stage != Stage::Ready {
            return Err(Error::Invalid(
                "preparation is incomplete; inspect or clean it up first",
            ));
        }
        self.verify_owned(&record)?;
        validate(&record.workspace.worktree_path)?;
        self.store.get_snapshot(
            record
                .baseline
                .clone()
                .ok_or(Error::Invalid("baseline missing"))?,
        )?;
        Ok(PreparedWorktree {
            preparation: record,
            _workspace: workspace_lease,
            _writer: writer,
            _repository: self.repository_lease.clone(),
        })
    }
    /// Only incomplete, still-clean, proven owned worktrees may be removed.
    /// No force, prune, recursive deletion or cleanup of completed sessions.
    pub fn cleanup_incomplete(&self, id: &WorkspaceId, recovery: Recovery) -> Result<()> {
        self.check_directory()?;
        let _writer = Lease::acquire_for(
            &self.directory.join("writer.lock"),
            &recovery,
            self.repository_lease.owner.clone(),
        )?;
        let mut record = self.load(id)?;
        let _workspace = Lease::acquire_for(
            &self.directory.join(id.as_str()).join("writer.lock"),
            &recovery,
            self.repository_lease.owner.clone(),
        )?;
        if !matches!(record.stage, Stage::Reserved | Stage::Created) {
            return Err(Error::Invalid(
                "only incomplete preparations may be cleaned up",
            ));
        }
        let path = &record.workspace.worktree_path;
        if path.symlink_metadata().is_ok() {
            self.verify_owned(&record)?;
            validate(path)?;
            if !git(
                path,
                [
                    "status",
                    "--porcelain=v1",
                    "-z",
                    "--untracked-files=all",
                    "--ignored",
                ],
            )?
            .is_empty()
            {
                return Err(Error::Invalid(
                    "incomplete worktree contains changes; retain it for inspection",
                ));
            }
            git(
                &self.repository.root,
                ["worktree", "unlock", path_text(path)?],
            )?;
            if let Err(error) = git(
                &self.repository.root,
                ["worktree", "remove", path_text(path)?],
            ) {
                let _ = git(
                    &self.repository.root,
                    [
                        "worktree",
                        "lock",
                        "--reason",
                        &format!("flow-{}", record.workspace.owner_token),
                        path_text(path)?,
                    ],
                );
                return Err(error);
            }
        } else {
            // A failed worktree add can leave shared administration behind.
            // Never prune it automatically, even if the working directory is gone.
            let listing = git(
                &self.repository.root,
                ["worktree", "list", "--porcelain", "-z"],
            )?;
            let field = format!("worktree {}", path_text(path)?);
            if listing.split(|b| *b == 0).any(|p| p == field.as_bytes()) {
                return Err(Error::Invalid(
                    "missing worktree has Git administration; inspect it manually",
                ));
            }
        }
        record.stage = Stage::Removed;
        self.save(&record)
    }
    fn save(&self, record: &Preparation) -> Result<()> {
        write(
            &self
                .directory
                .join(record.workspace.id.as_str())
                .join("preparation.json"),
            record,
        )
    }
    fn check_directory(&self) -> Result<()> {
        if self.directory.canonicalize()? != self.directory {
            return Err(Error::Invalid("owned worktree directory was redirected"));
        }
        Ok(())
    }
    fn load(&self, id: &WorkspaceId) -> Result<Preparation> {
        let suffix = id.as_str().strip_prefix("workspace-").unwrap_or_default();
        if suffix.len() != 32
            || !suffix
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(Error::Invalid("invalid owned workspace identity"));
        }
        let dir = self.directory.join(id.as_str());
        if !fs::symlink_metadata(&dir)?.is_dir() {
            return Err(Error::Invalid("workspace directory was replaced"));
        }
        let record: Preparation = read(&dir.join("preparation.json"))?;
        if record.workspace.id != *id
            || record.workspace.worktree_path != dir.join("tree")
            || record.workspace.git_common_dir != self.repository.common_dir
            || id.as_str() != format!("workspace-{}", record.workspace.owner_token)
        {
            return Err(Error::Invalid(
                "workspace ownership does not match its stored location",
            ));
        }
        Ok(record)
    }
    fn verify_owned(&self, record: &Preparation) -> Result<()> {
        verify_owned_record(record)
    }
}

pub(crate) fn verify_owned_record(record: &Preparation) -> Result<()> {
    let path = &record.workspace.worktree_path;
    if path.canonicalize()? != *path || !fs::symlink_metadata(path)?.is_dir() {
        return Err(Error::Invalid("workspace path escapes its owned directory"));
    }
    io::regular(&path.join(".git"))?;
    let common = PathBuf::from(text(git(
        path,
        ["rev-parse", "--path-format=absolute", "--git-common-dir"],
    )?)?)
    .canonicalize()?;
    let admin =
        PathBuf::from(text(git(path, ["rev-parse", "--absolute-git-dir"])?)?).canonicalize()?;
    if common != record.workspace.git_common_dir
        || admin.parent() != Some(common.join("worktrees").as_path())
    {
        return Err(Error::Invalid(
            "workspace Git administration is not owned by this repository",
        ));
    }
    let backlink = text(fs::read(admin.join("gitdir"))?)?;
    let reason = text(fs::read(admin.join("locked"))?)?;
    if Path::new(&backlink).canonicalize()? != path.join(".git").canonicalize()?
        || reason != format!("flow-{}", record.workspace.owner_token)
        || text(git(path, ["rev-parse", "HEAD"])?)? != record.workspace.base_commit
        || !fs::read(admin.join("HEAD"))?.starts_with(record.workspace.base_commit.as_bytes())
    {
        return Err(Error::Invalid(
            "workspace identity, detached HEAD or ownership marker changed; retain for inspection",
        ));
    }
    Ok(())
}

fn key(owner: &str, suffix: &str) -> Result<MutationKey> {
    MutationKey::try_from(format!("workspace-{owner}-{suffix}"))
        .map_err(|_| Error::Invalid("invalid mutation identity"))
}
fn path_text(path: &Path) -> Result<&str> {
    let text = path
        .to_str()
        .ok_or(Error::Invalid("non-UTF-8 paths are unsupported"))?;
    // Git for Windows accepts drive paths; canonical Rust paths use \\?\.
    #[cfg(windows)]
    let text = text.strip_prefix("\\\\?\\").unwrap_or(text);
    Ok(text)
}

fn validate(root: &Path) -> Result<()> {
    if !git(root, ["ls-files", "--unmerged", "-z"])?.is_empty() {
        return Err(Error::Invalid(
            "resolve the unmerged index before creating a session",
        ));
    }
    let config = git(root, ["config", "--null", "--list"])?;
    for entry in config.split(|b| *b == 0) {
        let name = entry.split(|b| *b == b'\n').next().unwrap_or_default();
        if name.starts_with(b"filter.")
            || name.eq_ignore_ascii_case(b"core.sparsecheckout")
            || name.eq_ignore_ascii_case(b"extensions.partialclone")
            || name.ends_with(b".promisor")
        {
            return Err(Error::Invalid(
                "sparse/partial repositories and checkout filters are unsupported for session preparation",
            ));
        }
    }
    let stages = git(root, ["ls-files", "--stage", "-z"])?;
    if stages.split(|b| *b == 0).any(|p| p.starts_with(b"160000 ")) {
        return Err(Error::Invalid(
            "submodule repositories are unsupported for session preparation",
        ));
    }
    let flags = git(root, ["ls-files", "-v", "-z"])?;
    if flags
        .split(|b| *b == 0)
        .any(|p| p.starts_with(b"S ") || p.first().is_some_and(u8::is_ascii_lowercase))
    {
        return Err(Error::Invalid(
            "clear skip-worktree/assume-unchanged flags before creating a session",
        ));
    }
    let paths = git(root, ["ls-files", "-z"])?;
    let attributes = git_input(
        root,
        [
            "check-attr",
            "--cached",
            "-z",
            "--stdin",
            "filter",
            "working-tree-encoding",
        ],
        Some(&paths),
    )?;
    let fields: Vec<_> = attributes.split(|b| *b == 0).collect();
    for attr in fields.chunks(3) {
        if attr.len() == 3 && ![b"unspecified".as_slice(), b"unset"].contains(&attr[2]) {
            return Err(Error::Invalid(
                "filter/LFS/encoded files are unsupported for clean baselines",
            ));
        }
    }
    Ok(())
}

/// Tracked paths bypass ignore rules. Untracked discovery has explicit local
/// ignore semantics; it never follows symlinks or reads an external link target.
pub fn capture(root: &Path) -> Result<BTreeMap<String, FileInput>> {
    stable_capture(|| capture_once(root, false))
}

/// Recorder coverage includes ignored/generated files; only Git control data is
/// outside the content domain. Limits fail closed rather than invent deletions.
pub fn capture_all(root: &Path) -> Result<BTreeMap<String, FileInput>> {
    stable_capture(|| capture_once(root, true))
}

fn stable_capture(
    mut scan: impl FnMut() -> Result<BTreeMap<String, FileInput>>,
) -> Result<BTreeMap<String, FileInput>> {
    let mut previous = None;
    for _ in 0..4 {
        match scan() {
            Ok(files) => {
                if previous.as_ref() == Some(&files) {
                    return Ok(files);
                }
                previous = Some(files);
            }
            Err(Error::Unstable) => previous = None,
            Err(error) => return Err(error),
        }
    }
    Err(Error::Unstable)
}

fn capture_once(root: &Path, include_ignored: bool) -> Result<BTreeMap<String, FileInput>> {
    let tracked = git(root, ["ls-files", "-z"])?;
    let mut paths = BTreeSet::new();
    for path in tracked.split(|b| *b == 0).filter(|p| !p.is_empty()) {
        paths.insert(
            std::str::from_utf8(path)
                .map_err(|_| Error::Invalid("non-UTF-8 paths are unsupported"))?
                .to_owned(),
        );
    }
    for entry in ignore::WalkBuilder::new(root)
        .hidden(false)
        .parents(false)
        .git_global(false)
        .git_ignore(!include_ignored)
        .git_exclude(!include_ignored)
        .ignore(!include_ignored)
        .follow_links(false)
        .filter_entry(|entry| entry.file_name() != ".git")
        .build()
    {
        let entry = entry.map_err(|_| Error::Invalid("workspace discovery failed"))?;
        if entry.path() == root {
            continue;
        }
        let relative = entry
            .path()
            .strip_prefix(root)
            .map_err(|_| Error::Invalid("path escaped workspace"))?;
        if relative.components().any(|p| p.as_os_str() == ".git") {
            continue;
        }
        if entry.file_type().is_some_and(|t| t.is_dir()) {
            if entry.path().join(".git").symlink_metadata().is_ok() {
                return Err(Error::Invalid("nested repositories are unsupported"));
            }
            continue;
        }
        paths.insert(path_text(relative)?.replace(std::path::MAIN_SEPARATOR, "/"));
    }
    if paths.len() > history::MAX_ENTRIES {
        return Err(Error::Invalid("workspace exceeds history path limit"));
    }
    let mut files = BTreeMap::new();
    let mut total = 0;
    'files: for relative in paths {
        if relative.contains('\\')
            || relative
                .split('/')
                .any(|p| p == ".git" || p.is_empty() || p == "." || p == "..")
        {
            return Err(Error::Invalid("unsafe workspace path"));
        }
        let mut path = root.to_owned();
        for component in Path::new(&relative).components() {
            let Component::Normal(name) = component else {
                return Err(Error::Invalid("path escaped workspace"));
            };
            path.push(name);
            if path != root.join(&relative) {
                match fs::symlink_metadata(&path) {
                    Ok(meta) if meta.is_dir() => {}
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue 'files,
                    Err(e) => return Err(e.into()),
                    _ => {
                        return Err(Error::Invalid(
                            "workspace path follows a symlink or non-directory",
                        ));
                    }
                }
            }
        }
        let meta = match fs::symlink_metadata(&path) {
            Ok(meta) => meta,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => continue, // tracked deletion
            Err(e) => return Err(e.into()),
        };
        if meta.is_dir() {
            continue;
        } // tracked file replaced by a directory
        let (kind, bytes) = if meta.file_type().is_symlink() {
            (FileKind::Symlink, link_bytes(&fs::read_link(&path)?)?)
        } else if meta.is_file() {
            let mut bytes = Vec::new();
            let file = File::open(&path)?;
            if identity(&file.metadata()?) != identity(&meta) {
                return Err(Error::Unstable);
            }
            #[cfg(windows)]
            {
                let opened = winapi_util::file::information(&file)?;
                let current = winapi_util::file::information(File::open(&path)?)?;
                if (opened.volume_serial_number(), opened.file_index())
                    != (current.volume_serial_number(), current.file_index())
                {
                    return Err(Error::Unstable);
                }
            }
            (&file)
                .take(history::MAX_BLOB_BYTES as u64 + 1)
                .read_to_end(&mut bytes)?;
            if identity(&file.metadata()?) != identity(&meta) {
                return Err(Error::Unstable);
            }
            (FileKind::Regular, bytes)
        } else {
            return Err(Error::Invalid(
                "nested repositories and special files are unsupported",
            ));
        };
        if identity(&fs::symlink_metadata(&path).map_err(|_| Error::Unstable)?) != identity(&meta) {
            return Err(Error::Unstable);
        }
        total += bytes.len();
        if bytes.len() > history::MAX_BLOB_BYTES || total > history::MAX_CAPTURE_BYTES {
            return Err(Error::Invalid("workspace exceeds capture size limit"));
        }
        files.insert(
            relative,
            FileInput {
                bytes,
                mode: file_mode(&meta),
                kind,
            },
        );
    }
    Ok(files)
}
#[cfg(unix)]
fn file_mode(meta: &fs::Metadata) -> u32 {
    meta.permissions().mode() & 0o7777
}
#[cfg(windows)]
fn file_mode(meta: &fs::Metadata) -> u32 {
    if meta.permissions().readonly() {
        0o444
    } else {
        0o644
    }
}
#[cfg(unix)]
fn link_bytes(path: &Path) -> Result<Vec<u8>> {
    Ok(path.as_os_str().as_bytes().to_vec())
}
#[cfg(windows)]
fn link_bytes(path: &Path) -> Result<Vec<u8>> {
    Ok(path_text(path)?.as_bytes().to_vec())
}
#[cfg(unix)]
fn identity(meta: &fs::Metadata) -> (u64, u64, u64, u32, i64, i64, i64, i64) {
    use std::os::unix::fs::MetadataExt;
    (
        meta.dev(),
        meta.ino(),
        meta.size(),
        meta.mode(),
        meta.mtime(),
        meta.mtime_nsec(),
        meta.ctime(),
        meta.ctime_nsec(),
    )
}
#[cfg(windows)]
fn identity(meta: &fs::Metadata) -> (u64, u64, u64, u32) {
    use std::os::windows::fs::MetadataExt;
    (
        meta.creation_time(),
        meta.last_write_time(),
        meta.file_size(),
        meta.file_attributes(),
    )
}
fn source_fingerprint(root: &Path) -> Result<ContentId> {
    let head = git(root, ["rev-parse", "HEAD"])?;
    let git_dir = PathBuf::from(text(git(root, ["rev-parse", "--absolute-git-dir"])?)?);
    let index = fs::read(git_dir.join("index"))?;
    let files: Vec<_> = capture(root)?
        .into_iter()
        .map(|(p, f)| (p, ContentId::hash(&f.bytes), f.mode, f.kind))
        .collect();
    let bytes = serde_json::to_vec(&(
        head,
        fs::read(git_dir.join("HEAD"))?,
        ContentId::hash(&index),
        files,
    ))
    .map_err(|_| Error::Invalid("cannot encode source fingerprint"))?;
    Ok(ContentId::hash(&bytes))
}

#[cfg(all(test, unix))]
mod tests;
