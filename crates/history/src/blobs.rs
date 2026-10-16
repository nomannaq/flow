use crate::{ContentId, Error, MAX_BLOB_BYTES, Result};
use std::{
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
};

pub(crate) fn directory(path: &Path) -> Result<()> {
    if let Ok(meta) = fs::symlink_metadata(path) {
        if !meta.is_dir() || meta.file_type().is_symlink() {
            return Err(Error::Invalid("history directory must not be a symlink"));
        }
    } else {
        fs::create_dir(path)?;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    }
    // An existing entry may be left by an interrupted earlier creation.
    sync_dir(
        path.parent()
            .ok_or(Error::Invalid("history directory has no parent"))?,
    )?;
    Ok(())
}
pub(crate) fn sync_dir(path: &Path) -> Result<()> {
    #[cfg(unix)]
    File::open(path)?.sync_all()?;
    // Windows has no unprivileged directory fsync equivalent. File contents and
    // SQLite commits are flushed separately; directory durability uses NTFS's
    // journal. Crash/power-loss acceptance is a separate Windows release gate.
    #[cfg(windows)]
    if !fs::symlink_metadata(path)?.is_dir() {
        return Err(Error::Invalid("history directory was replaced"));
    }
    Ok(())
}
pub(crate) fn sync_file(path: &Path) -> Result<()> {
    // FlushFileBuffers requires a writable handle on Windows.
    let mut options = File::options();
    options.read(true);
    #[cfg(windows)]
    options.write(true);
    options.open(path)?.sync_all()?;
    Ok(())
}
pub(crate) fn regular(path: &Path) -> Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if !meta.is_file() || meta.file_type().is_symlink() {
        return Err(Error::Invalid("history file must be a regular file"));
    }
    Ok(())
}
pub(crate) struct Blobs {
    pub root: PathBuf,
}
impl Blobs {
    pub fn path(&self, id: &ContentId) -> PathBuf {
        self.root.join(format!("{}.zst", id.as_str()))
    }
    pub fn put(&self, bytes: &[u8]) -> Result<ContentId> {
        if bytes.len() > MAX_BLOB_BYTES {
            return Err(Error::Invalid("history content exceeds limit"));
        }
        let id = ContentId::hash(bytes);
        let path = self.path(&id);
        if fs::symlink_metadata(&path).is_ok() {
            self.read(&id, bytes.len() as u64)?;
            sync_file(&path)?;
            sync_dir(&self.root)?;
            return Ok(id);
        }
        let mut temp = tempfile::NamedTempFile::new_in(&self.root)?;
        {
            let mut encoder = zstd::stream::write::Encoder::new(temp.as_file_mut(), 3)?;
            encoder.write_all(bytes)?;
            encoder.finish()?;
        }
        temp.as_file().sync_all()?;
        match temp.persist_noclobber(&path) {
            Ok(_) => {}
            Err(e) if e.error.kind() == std::io::ErrorKind::AlreadyExists => {
                self.read(&id, bytes.len() as u64)?;
                sync_file(&path)?;
            }
            Err(e) => return Err(Error::Io(e.error)),
        }
        sync_dir(&self.root)?;
        Ok(id)
    }
    pub fn read(&self, id: &ContentId, size: u64) -> Result<Vec<u8>> {
        if size > MAX_BLOB_BYTES as u64 {
            return Err(Error::CorruptContent(id.clone()));
        }
        let path = self.path(id);
        match regular(&path) {
            Err(Error::Io(e)) if e.kind() == std::io::ErrorKind::NotFound => {
                return Err(Error::MissingContent(id.clone()));
            }
            Err(_) => return Err(Error::CorruptContent(id.clone())),
            Ok(()) => {}
        }
        let file = File::open(path)?;
        if file.metadata()?.len() > MAX_BLOB_BYTES as u64 + 1024 * 1024 {
            return Err(Error::CorruptContent(id.clone()));
        }
        let mut decoder = zstd::stream::read::Decoder::new(file)
            .map_err(|_| Error::CorruptContent(id.clone()))?;
        decoder
            .window_log_max(26)
            .map_err(|_| Error::CorruptContent(id.clone()))?;
        let mut bytes = Vec::new();
        decoder
            .take(size + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| Error::CorruptContent(id.clone()))?;
        if bytes.len() as u64 != size || ContentId::hash(&bytes) != *id {
            return Err(Error::CorruptContent(id.clone()));
        }
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn b26_bounded_decompression_rejects_bombs_truncation_and_seeded_corruption() {
        let temporary = tempfile::tempdir().unwrap();
        let blobs = Blobs {
            root: temporary.path().to_owned(),
        };
        let bytes = vec![b'x'; 1024 * 1024];
        let id = ContentId::hash(&bytes);
        let compressed = zstd::stream::encode_all(bytes.as_slice(), 3).unwrap();
        fs::write(blobs.path(&id), &compressed).unwrap();
        // A forged small advertised length must stop at size + 1.
        for size in [0, 1, 32, 4096, MAX_BLOB_BYTES as u64 + 1] {
            assert!(matches!(
                blobs.read(&id, size),
                Err(Error::CorruptContent(_))
            ));
        }
        for length in [0, 1, 4, compressed.len() / 2, compressed.len() - 1] {
            fs::write(blobs.path(&id), &compressed[..length]).unwrap();
            assert!(
                blobs.read(&id, bytes.len() as u64).is_err(),
                "truncated={length}"
            );
        }
        let mut seed = 26u64;
        for case in 0..128 {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
            let mut damaged = compressed.clone();
            let offset = seed as usize % damaged.len();
            damaged[offset] ^= 1 << (case % 8);
            fs::write(blobs.path(&id), damaged).unwrap();
            // Padding-bit mutations can be valid. No accepted decode may differ.
            if let Ok(decoded) = blobs.read(&id, bytes.len() as u64) {
                assert_eq!(decoded, bytes, "seed={seed} case={case}");
            }
        }
    }
}
