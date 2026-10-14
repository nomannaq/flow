use super::{Error, Result};
use serde::{Serialize, de::DeserializeOwned};
use std::{
    ffi::OsStr,
    fs::{self, File, OpenOptions},
    io::{Read, Seek, Write},
    os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
    path::Path,
    process::{Child, Command, Stdio},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub(super) fn now() -> Result<u64> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| Error::Invalid("system clock precedes epoch"))?
        .as_millis() as u64)
}
pub(super) fn token() -> Result<String> {
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
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    File::open(path.parent().ok_or(Error::Invalid("missing parent"))?)?.sync_all()?;
    Ok(())
}
pub(super) fn regular(path: &Path) -> Result<File> {
    let meta = fs::symlink_metadata(path)?;
    if !meta.is_file() || meta.nlink() != 1 {
        return Err(Error::Invalid(
            "ownership file must be a regular file with one link",
        ));
    }
    Ok(File::open(path)?)
}
pub(super) fn read<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let mut bytes = Vec::new();
    regular(path)?.take(64 * 1024 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > 64 * 1024 {
        return Err(Error::Invalid("ownership metadata exceeds limit"));
    }
    serde_json::from_slice(&bytes)
        .map_err(|_| Error::Invalid("ownership metadata is corrupt; inspect it before recovery"))
}
pub(super) fn write<T: Serialize>(path: &Path, value: &T) -> Result<()> {
    if path.symlink_metadata().is_ok() {
        regular(path)?;
    }
    let parent = path.parent().ok_or(Error::Invalid("missing parent"))?;
    let mut temp = tempfile::NamedTempFile::new_in(parent)?;
    serde_json::to_writer(temp.as_file_mut(), value)
        .map_err(|_| Error::Invalid("cannot encode ownership"))?;
    temp.as_file().sync_all()?;
    temp.persist(path).map_err(|e| Error::Io(e.error))?;
    File::open(parent)?.sync_all()?;
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
}
impl Lease {
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
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .open(path)?;
        file.try_lock().map_err(|e| match e {
            std::fs::TryLockError::WouldBlock => Error::Busy,
            std::fs::TryLockError::Error(e) => Error::Io(e),
        })?;
        // A failed/stale acquisition must NOT clear the previous owner's metadata.
        if file.metadata()?.len() != 0 {
            let owner: Owner = read(path)?;
            if !matches!(recovery, Recovery::Acknowledge(expected) if *expected == owner.instance) {
                return Err(Error::StaleOwner(owner));
            }
        }
        file.set_len(0)?;
        file.rewind()?;
        serde_json::to_writer(&mut file, &owner)
            .map_err(|_| Error::Invalid("cannot encode lease"))?;
        file.sync_all()?;
        File::open(path.parent().ok_or(Error::Invalid("missing parent"))?)?.sync_all()?;
        Ok(Self { file, owner })
    }
}
impl Drop for Lease {
    fn drop(&mut self) {
        // Keep the same inode: unlinking a lock lets concurrent callers lock
        // different inodes. If clearing fails, next open requires recovery.
        let _ = self.file.set_len(0).and_then(|()| self.file.sync_all());
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
    let mut child = GitChild(
        Command::new("/usr/bin/git")
            .args([
                "-c",
                "core.hooksPath=/dev/null",
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
            .env("PATH", "/usr/bin:/bin")
            .env("LC_ALL", "C")
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
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
