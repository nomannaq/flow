use super::{Error, Result};
use serde::{Serialize, de::DeserializeOwned};
#[cfg(unix)]
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::{
    ffi::OsStr,
    fs::{self, File, OpenOptions},
    io::{Read, Seek, Write},
    path::Path,
    process::{Child, Command, Stdio},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub(super) fn sync_dir(path: &Path) -> Result<()> {
    #[cfg(unix)]
    File::open(path)?.sync_all()?;
    // Windows directory entries use NTFS journaling; see native packaging guide.
    #[cfg(windows)]
    if !fs::symlink_metadata(path)?.is_dir() {
        return Err(Error::Invalid("owned directory was replaced"));
    }
    Ok(())
}

pub(crate) fn now() -> Result<u64> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| Error::Invalid("system clock precedes epoch"))?
        .as_millis() as u64)
}
pub(crate) fn token() -> Result<String> {
    let mut bytes = [0; 16];
    getrandom::fill(&mut bytes).map_err(|_| Error::Invalid("ownership entropy unavailable"))?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}
pub(super) fn directory(path: &Path) -> Result<()> {
    match fs::create_dir(path) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
        Err(e) => return Err(e.into()),
    }
    if !fs::symlink_metadata(path)?.is_dir() {
        return Err(Error::Invalid("owned directory was replaced"));
    }
    #[cfg(unix)]
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    sync_dir(path.parent().ok_or(Error::Invalid("missing parent"))?)?;
    Ok(())
}
pub(super) fn regular(path: &Path) -> Result<File> {
    let meta = fs::symlink_metadata(path)?;
    let file = File::open(path)?;
    #[cfg(unix)]
    let links = meta.nlink();
    #[cfg(windows)]
    let links = winapi_util::file::information(&file)?.number_of_links();
    if !meta.is_file() || meta.file_type().is_symlink() || links != 1 {
        return Err(Error::Invalid(
            "ownership file must be a regular file with one link",
        ));
    }
    Ok(file)
}
pub(crate) fn read<T: DeserializeOwned>(path: &Path) -> Result<T> {
    decode(regular(path)?)
}
fn decode<T: DeserializeOwned>(source: impl Read) -> Result<T> {
    let mut bytes = Vec::new();
    source.take(64 * 1024 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > 64 * 1024 {
        return Err(Error::Invalid("ownership metadata exceeds limit"));
    }
    serde_json::from_slice(&bytes)
        .map_err(|_| Error::Invalid("ownership metadata is corrupt; inspect it before recovery"))
}
pub(crate) fn write<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if path.symlink_metadata().is_ok() {
        regular(path)?;
    }
    let parent = path.parent().ok_or(Error::Invalid("missing parent"))?;
    let mut temp = tempfile::NamedTempFile::new_in(parent)?;
    serde_json::to_writer(temp.as_file_mut(), value)
        .map_err(|_| Error::Invalid("cannot encode ownership"))?;
    temp.as_file().sync_all()?;
    temp.persist(path).map_err(|e| Error::Io(e.error))?;
    sync_dir(parent)?;
    Ok(())
}

/// OS lock is authoritative for liveness. PID is diagnostic only; a random
/// instance token prevents PID reuse from authorizing stale-owner recovery.
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct Owner {
    pub pid: u32,
    pub instance: String,
    pub started_at_ms: u64,
}
#[derive(Clone, Debug, Default)]
pub enum Recovery {
    #[default]
    RefuseStale,
    /// Explicitly acknowledge the exact owner returned in Error::StaleOwner.
    Acknowledge(String),
}
pub(super) struct Lease {
    file: File,
    pub owner: Owner,
    preserve: std::sync::atomic::AtomicBool,
}
impl Lease {
    pub fn preserve_owner(&self) {
        self.preserve
            .store(true, std::sync::atomic::Ordering::SeqCst);
    }
    pub fn acquire(path: &Path, recovery: &Recovery) -> Result<Self> {
        Self::acquire_for(
            path,
            recovery,
            Owner {
                pid: std::process::id(),
                instance: token()?,
                started_at_ms: now()?,
            },
        )
    }
    pub fn acquire_for(path: &Path, recovery: &Recovery, owner: Owner) -> Result<Self> {
        if path.symlink_metadata().is_ok() {
            regular(path)?;
        }
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true).truncate(false);
        #[cfg(unix)]
        options.mode(0o600);
        let mut file = options.open(path)?;
        history::try_lock_file(&file).map_err(|error| match error {
            std::fs::TryLockError::WouldBlock => Error::Busy,
            std::fs::TryLockError::Error(error) => Error::Io(error),
        })?;
        // A failed/stale acquisition must NOT clear the previous owner's metadata.
        if file.metadata()?.len() != 0 {
            // Windows locks are mandatory: a second handle cannot read our
            // exclusively locked bytes. Inspect through the owning handle.
            let owner: Owner = decode(&mut file)?;
            if !matches!(recovery, Recovery::Acknowledge(expected) if *expected == owner.instance) {
                return Err(Error::StaleOwner(owner));
            }
        }
        file.set_len(0)?;
        file.rewind()?;
        serde_json::to_writer(&mut file, &owner)
            .map_err(|_| Error::Invalid("cannot encode lease"))?;
        file.sync_all()?;
        sync_dir(path.parent().ok_or(Error::Invalid("missing parent"))?)?;
        Ok(Self {
            file,
            owner,
            preserve: std::sync::atomic::AtomicBool::new(false),
        })
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        // Keep the same inode: unlinking a lock lets concurrent callers lock
        // different inodes. If clearing fails, next open requires recovery.
        if !self.preserve.load(std::sync::atomic::Ordering::SeqCst) {
            let _ = self.file.set_len(0).and_then(|()| self.file.sync_all());
        }
    }
}

/// No shell, inherited Git variables, hooks, network or external diff driver.
/// Repository filter commands are rejected before checkout by validate().
pub(super) fn git<I, S>(root: &Path, args: I) -> Result<Vec<u8>>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    git_input(root, args, None)
}
struct GitChild(Child);
impl Drop for GitChild {
    fn drop(&mut self) {
        // Also reap on polling/output I/O failures, not just the timeout path.
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}
pub(super) fn git_input<I, S>(root: &Path, args: I, input: Option<&[u8]>) -> Result<Vec<u8>>
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    const MAX_OUTPUT: u64 = 8 * 1024 * 1024;
    let mut stdout = tempfile::tempfile()?;
    let mut stdin = tempfile::tempfile()?;
    if let Some(bytes) = input {
        stdin.write_all(bytes)?;
        stdin.rewind()?;
    }
    let mut command = Command::new("git");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    let mut child = GitChild(
        command
            .args([
                "-c",
                if cfg!(windows) {
                    "core.hooksPath=NUL"
                } else {
                    "core.hooksPath=/dev/null"
                },
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.autocrlf=false",
                "-c",
                "submodule.recurse=false",
                "-c",
                "protocol.allow=never",
                "-c",
                "gc.auto=0",
                "-c",
                "maintenance.auto=false",
                "-c",
                "checkout.workers=1",
            ])
            .args(args)
            .current_dir(root)
            .env_clear()
            .envs(
                ["PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP"]
                    .iter()
                    .filter_map(|name| std::env::var_os(name).map(|value| (*name, value))),
            )
            .env("LC_ALL", "C")
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env(
                "GIT_CONFIG_GLOBAL",
                if cfg!(windows) { "NUL" } else { "/dev/null" },
            )
            .env("GIT_TERMINAL_PROMPT", "0")
            .env("GIT_OPTIONAL_LOCKS", "0")
            .stdin(stdin)
            .stdout(stdout.try_clone()?)
            .stderr(Stdio::null())
            .spawn()?,
    );
    let started = Instant::now();
    let status = loop {
        if stdout.metadata()?.len() > MAX_OUTPUT || started.elapsed() > Duration::from_secs(30) {
            return Err(Error::Invalid(
                "Git exceeded time/output limit; inspect retained preparation",
            ));
        }
        if let Some(status) = child.0.try_wait()? {
            break status;
        }
        std::thread::sleep(Duration::from_millis(10));
    };
    if !status.success() {
        return Err(Error::Git(status.code()));
    }
    stdout.rewind()?;
    let mut bytes = Vec::new();
    stdout.take(MAX_OUTPUT + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_OUTPUT {
        return Err(Error::Invalid("Git output exceeds limit"));
    }
    Ok(bytes)
}
pub(super) fn text(bytes: Vec<u8>) -> Result<String> {
    let text = String::from_utf8(bytes)
        .map_err(|_| Error::Invalid("non-UTF-8 Git paths are unsupported"))?;
    Ok(text.strip_suffix('\n').unwrap_or(&text).to_owned())
}

#[cfg(all(test, windows))]
#[test]
fn stale_lease_is_read_through_its_locked_handle_and_requires_the_exact_owner() {
    let scratch = tempfile::tempdir().unwrap();
    let path = scratch.path().join("writer.lock");
    let lease = Lease::acquire(&path, &Recovery::RefuseStale).unwrap();
    let owner = lease.owner.instance.clone();
    lease.preserve_owner();
    drop(lease);
    assert!(matches!(
        Lease::acquire(&path, &Recovery::RefuseStale),
        Err(Error::StaleOwner(_))
    ));
    assert!(matches!(
        Lease::acquire(&path, &Recovery::Acknowledge("wrong".into())),
        Err(Error::StaleOwner(_))
    ));
    drop(Lease::acquire(&path, &Recovery::Acknowledge(owner)).unwrap());
    assert!(Lease::acquire(&path, &Recovery::RefuseStale).is_ok());
}
