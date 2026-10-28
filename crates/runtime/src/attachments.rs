//! Explicit host-selected file snapshots. Nothing reads repository-provided paths.
use agent_client_protocol::schema::v1 as acp;
use base64::{Engine as _, engine::general_purpose::STANDARD};
use history::{AttachmentInput, HistoryStore};
use protocol::AttachmentRef;
use std::{
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
};

pub fn import(store: &HistoryStore, paths: Vec<String>) -> history::Result<Vec<AttachmentRef>> {
    if paths.is_empty() || paths.len() > 32 {
        return Err(history::Error::Invalid("select up to 32 files or folders"));
    }
    let mut files = Vec::new();
    for source in paths {
        let path = PathBuf::from(source);
        if !path.is_absolute() || path.to_string_lossy().len() > 4096 {
            return Err(history::Error::Invalid("select a local absolute path"));
        }
        let canonical = path.canonicalize()?;
        let root = if canonical.is_dir() {
            canonical.parent().unwrap_or(&canonical)
        } else {
            canonical
                .parent()
                .ok_or(history::Error::Invalid("invalid attachment path"))?
        };
        collect(&canonical, root, &mut files, 0)?;
    }
    let mut inputs = Vec::new();
    let mut total = 0usize;
    for (path, name) in files {
        let meta = fs::symlink_metadata(&path)?;
        if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > 32 * 1024 * 1024 {
            return Err(history::Error::Invalid(
                "attach regular files up to 32 MiB each",
            ));
        }
        let mut options = fs::OpenOptions::new();
        options.read(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.custom_flags(rustix::fs::OFlags::NOFOLLOW.bits() as i32);
        }
        let file = options.open(&path)?;
        let mut bytes = Vec::new();
        file.take(32 * 1024 * 1024 + 1).read_to_end(&mut bytes)?;
        if bytes.len() > 32 * 1024 * 1024
            || fs::symlink_metadata(&path)?.modified()? != meta.modified()?
            || bytes.len() as u64 != meta.len()
        {
            return Err(history::Error::Invalid(
                "attachment changed while reading; select it again",
            ));
        }
        total = total.saturating_add(bytes.len());
        if total > 64 * 1024 * 1024 {
            return Err(history::Error::Invalid("attachments exceed 64 MiB"));
        }
        let mime_type = mime_guess::from_path(&path)
            .first_or_octet_stream()
            .to_string();
        inputs.push(AttachmentInput {
            name,
            mime_type,
            bytes,
        });
    }
    store.import_attachments(inputs)
}
fn collect(
    path: &Path,
    root: &Path,
    files: &mut Vec<(PathBuf, String)>,
    depth: usize,
) -> history::Result<()> {
    let meta = fs::symlink_metadata(path)?;
    if meta.file_type().is_symlink() {
        return Err(history::Error::Invalid(
            "folder attachments cannot contain symbolic links",
        ));
    }
    if meta.is_dir() {
        if depth > 16 {
            return Err(history::Error::Invalid(
                "attachment folder is too deeply nested",
            ));
        }
        let mut entries = fs::read_dir(path)?.collect::<Result<Vec<_>, _>>()?;
        if entries.len() > 256 {
            return Err(history::Error::Invalid(
                "folder is too large; select specific files",
            ));
        }
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            if entry.file_name() == ".git" {
                continue;
            }
            collect(&entry.path(), root, files, depth + 1)?;
        }
    } else {
        if !meta.is_file() || files.len() >= 32 {
            return Err(history::Error::Invalid(
                "attach at most 32 regular files; choose a smaller folder",
            ));
        }
        let name = path
            .strip_prefix(root)
            .map_err(|_| history::Error::Conflict)?
            .to_string_lossy()
            .replace('\\', "/");
        files.push((path.into(), name));
    }
    Ok(())
}

pub fn materialize(store: &HistoryStore, reference: &AttachmentRef) -> history::Result<PathBuf> {
    let bytes = store.attachment_bytes(reference.clone())?;
    let root = store.directory().join("attachment-files");
    for path in [&root, &root.join(&reference.id)] {
        if !path.exists() {
            fs::create_dir(path)?;
        }
        let meta = fs::symlink_metadata(path)?;
        if !meta.is_dir() || meta.file_type().is_symlink() {
            return Err(history::Error::Conflict);
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
        }
    }
    let name = reference
        .name
        .rsplit(['/', '\\'])
        .next()
        .filter(|n| !n.is_empty() && *n != "." && *n != "..")
        .ok_or(history::Error::Conflict)?;
    let path = root.join(&reference.id).join(name);
    if path.symlink_metadata().is_ok() {
        let meta = fs::symlink_metadata(&path)?;
        if !meta.is_file()
            || meta.file_type().is_symlink()
            || meta.len() != bytes.len() as u64
            || fs::read(&path)? != bytes
        {
            return Err(history::Error::Conflict);
        }
    } else {
        let mut tmp = tempfile::NamedTempFile::new_in(path.parent().unwrap())?;
        tmp.write_all(&bytes)?;
        tmp.as_file().sync_all()?;
        tmp.persist_noclobber(&path)
            .map_err(|e| history::Error::Io(e.error))?;
        let mut permissions = fs::metadata(&path)?.permissions();
        permissions.set_readonly(true);
        fs::set_permissions(&path, permissions)?;
    }
    Ok(path)
}

pub fn blocks(
    store: &HistoryStore,
    refs: &[AttachmentRef],
    caps: &serde_json::Value,
) -> history::Result<Vec<acp::ContentBlock>> {
    let mut result = Vec::new();
    let mut inline = 0usize;
    store.validate_attachments(refs.to_vec())?;
    for reference in refs {
        let path = materialize(store, reference)?;
        let uri = url::Url::from_file_path(path)
            .map_err(|_| history::Error::Invalid("attachment URI is unavailable"))?
            .to_string();
        let bytes = store.attachment_bytes(reference.clone())?;
        let capability = &caps["promptCapabilities"];
        let small = inline + bytes.len() <= 512 * 1024;
        let block = if small
            && matches!(
                reference.mime_type.as_str(),
                "image/png" | "image/jpeg" | "image/gif" | "image/webp"
            )
            && capability["image"] == true
        {
            inline += bytes.len();
            acp::ContentBlock::Image(acp::ImageContent::new(
                STANDARD.encode(&bytes),
                reference.mime_type.clone(),
            ))
        } else if small && reference.mime_type.starts_with("audio/") && capability["audio"] == true
        {
            inline += bytes.len();
            acp::ContentBlock::Audio(acp::AudioContent::new(
                STANDARD.encode(&bytes),
                reference.mime_type.clone(),
            ))
        } else if small
            && (reference.mime_type.starts_with("text/")
                || matches!(
                    reference.mime_type.as_str(),
                    "application/json"
                        | "application/xml"
                        | "application/javascript"
                        | "application/yaml"
                        | "application/toml"
                ))
            && capability["embeddedContext"] == true
            && !bytes.contains(&0)
            && let Ok(text) = std::str::from_utf8(&bytes)
        {
            inline += bytes.len();
            acp::ContentBlock::Resource(acp::EmbeddedResource::new(
                acp::EmbeddedResourceResource::TextResourceContents(
                    acp::TextResourceContents::new(text, uri)
                        .mime_type(reference.mime_type.clone()),
                ),
            ))
        } else {
            acp::ContentBlock::ResourceLink(
                acp::ResourceLink::new(reference.name.clone(), uri)
                    .mime_type(reference.mime_type.clone())
                    .size(reference.size as i64),
            )
        };
        result.push(block);
    }
    Ok(result)
}

pub fn validate_refs(refs: &[AttachmentRef]) -> Result<(), agent_client_protocol::Error> {
    if refs.len() > 32
        || refs.iter().map(|r| r.size as u64).sum::<u64>() > 64 * 1024 * 1024
        || refs.iter().any(|r| {
            r.id.len() != 64
                || !r.id.bytes().all(|b| b.is_ascii_hexdigit())
                || r.size > 32 * 1024 * 1024
                || r.name.is_empty()
                || r.name.len() > 1024
                || r.name.chars().any(char::is_control)
                || r.mime_type.len() > 128
                || !r.mime_type.contains('/')
                || r.mime_type.chars().any(char::is_control)
        })
    {
        return Err(agent_client_protocol::Error::invalid_params());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn store(temp: &Path) -> HistoryStore {
        let project = temp.join("project");
        let data = temp.join("data");
        fs::create_dir(&project).unwrap();
        fs::create_dir(&data).unwrap();
        HistoryStore::open(&project, &data).unwrap()
    }
    #[test]
    fn files_are_frozen_and_all_acp_content_kinds_follow_capabilities() {
        let temp = tempfile::tempdir().unwrap();
        let store = store(temp.path());
        let source = temp.path().join("reference files");
        fs::create_dir(&source).unwrap();
        for (name, bytes) in [
            ("report.pdf", b"%PDF-1.7\noriginal".as_slice()),
            ("code.rs", b"fn original() {}".as_slice()),
            ("sound.wav", b"RIFFsound".as_slice()),
            ("diagram.webp", b"RIFFimage".as_slice()),
            ("archive.zip", b"PK\x00\x00".as_slice()),
        ] {
            fs::write(source.join(name), bytes).unwrap();
        }
        let files = import(&store, vec![source.to_string_lossy().into_owned()]).unwrap();
        assert_eq!(files.len(), 5);
        fs::write(source.join("report.pdf"), b"changed").unwrap();
        let linked = blocks(&store, &files, &json!({})).unwrap();
        assert!(
            linked
                .iter()
                .all(|b| matches!(b, acp::ContentBlock::ResourceLink(_)))
        );
        let pdf = files
            .iter()
            .find(|f| f.name.ends_with("report.pdf"))
            .unwrap();
        let frozen = materialize(&store, pdf).unwrap();
        assert_eq!(fs::read(&frozen).unwrap(), b"%PDF-1.7\noriginal");
        assert!(fs::metadata(&frozen).unwrap().permissions().readonly());
        for block in &linked {
            let acp::ContentBlock::ResourceLink(link) = block else {
                unreachable!()
            };
            assert!(
                url::Url::parse(&link.uri)
                    .unwrap()
                    .to_file_path()
                    .unwrap()
                    .exists()
            );
        }
        let rich = blocks(
            &store,
            &files,
            &json!({"promptCapabilities":{"image":true,"audio":true,"embeddedContext":true}}),
        )
        .unwrap();
        assert!(
            rich.iter()
                .any(|b| matches!(b, acp::ContentBlock::Image(_)))
        );
        assert!(
            rich.iter()
                .any(|b| matches!(b, acp::ContentBlock::Audio(_)))
        );
        assert!(
            rich.iter()
                .any(|b| matches!(b, acp::ContentBlock::Resource(_)))
        );
        assert!(
            rich.iter()
                .any(|b| matches!(b, acp::ContentBlock::ResourceLink(_)))
        );
        let mut forged = pdf.clone();
        forged.name = "forged.pdf".into();
        assert!(materialize(&store, &forged).is_err());
        drop(store);
        let store =
            HistoryStore::open(&temp.path().join("project"), &temp.path().join("data")).unwrap();
        assert_eq!(
            fs::read(materialize(&store, pdf).unwrap()).unwrap(),
            b"%PDF-1.7\noriginal"
        );
    }
    #[test]
    fn unsafe_and_oversized_folder_imports_do_not_silently_truncate() {
        let temp = tempfile::tempdir().unwrap();
        let store = store(temp.path());
        assert!(import(&store, vec!["relative.txt".into()]).is_err());
        let folder = temp.path().join("many");
        fs::create_dir(&folder).unwrap();
        for i in 0..33 {
            fs::write(folder.join(format!("{i}.txt")), b"x").unwrap();
        }
        assert!(import(&store, vec![folder.to_string_lossy().into_owned()]).is_err());
        let huge = temp.path().join("large.pdf");
        fs::File::create(&huge)
            .unwrap()
            .set_len(32 * 1024 * 1024 + 1)
            .unwrap();
        assert!(import(&store, vec![huge.to_string_lossy().into_owned()]).is_err());
        #[cfg(unix)]
        {
            let linked = temp.path().join("linked");
            fs::create_dir(&linked).unwrap();
            std::os::unix::fs::symlink(&huge, linked.join("external")).unwrap();
            assert!(import(&store, vec![linked.to_string_lossy().into_owned()]).is_err());
        }
    }
}
