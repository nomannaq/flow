//! Bounded immutable review queries. Only history.location consults live paths.
use history::{FileEntry, HistoryStore};
use protocol::{AgentCommand, HistoryContentIds, HistoryQuery, Method, SessionList};
use serde_json::{Value, json};

const PAGE: u16 = 20;
pub fn request(store: HistoryStore, method: Method, params: Value) -> history::Result<Value> {
    if method == Method::SessionList {
        let query: SessionList = decode(params)?;
        let page = store.sessions_page(query.after, PAGE)?;
        return Ok(
            json!({"items":page.items.iter().map(|s|json!({"id":s.id,"status":s.status,"createdAtMs":s.created_at_ms.to_string(),"workspaceId":s.workspace_id})).collect::<Vec<_>>(),"next":page.next}),
        );
    }
    if method == Method::SessionArchive {
        let query: AgentCommand = decode(params)?;
        store.archive_session(query.session_id)?;
        return Ok(Value::Null);
    }
    let query: HistoryQuery = decode(params)?;
    let session = store.get_session(query.session_id.clone())?;
    if method == Method::HistoryTurns {
        let page = store.turns(query.session_id.clone(), query.after, PAGE)?;
        return Ok(
            json!({"items":page.items.iter().map(|t|json!({"id":t.id,"status":t.status,"createdAtMs":t.created_at_ms.to_string()})).collect::<Vec<_>>(),"next":page.next,"captureGap":store.capture_gap(query.session_id)?}),
        );
    }
    if let Some(turn) = &query.turn_id
        && store.get_turn(turn.clone())?.session_id != query.session_id
    {
        return Err(history::Error::Conflict);
    }
    let after = query
        .after
        .as_deref()
        .unwrap_or("0")
        .parse::<u64>()
        .map_err(|_| history::Error::InvalidCursor)?;
    if method == Method::HistoryOperations {
        let page =
            store.operations_for_turn(query.session_id.clone(), query.turn_id, after, PAGE)?;
        let items = page.items.iter().map(|o| {
            let before=store.get_snapshot(o.before.clone())?;
            let after=store.get_snapshot(o.after.clone())?;
            Ok(json!({"id":o.id,"turnId":o.turn_id,"status":o.status,"attribution":o.attribution,
                "completedAtMs":o.completed_at_ms.to_string(),"evidence":o.evidence.chars().take(512).collect::<String>(),
                "beforeSnapshotId":o.before,"afterSnapshotId":o.after,
                "complete":before.manifest.coverage.complete && after.manifest.coverage.complete,
                "excludedBefore":before.manifest.coverage.excluded.len(),"excludedAfter":after.manifest.coverage.excluded.len()}))
        }).collect::<history::Result<Vec<_>>>()?;
        return Ok(
            json!({"items":items,"next":page.next.map(|n|n.to_string()),"captureGap":store.capture_gap(query.session_id)?}),
        );
    }
    let operation = store.get_operation(
        query
            .operation_id
            .ok_or(history::Error::Invalid("operation required"))?,
    )?;
    if operation.input.session_id != query.session_id
        || query
            .turn_id
            .as_ref()
            .is_some_and(|t| t != &operation.input.turn_id)
    {
        return Err(history::Error::Conflict);
    }
    if method == Method::HistoryFiles {
        let start = usize::try_from(after).map_err(|_| history::Error::InvalidCursor)?;
        if start > operation.changes.len() {
            return Err(history::Error::InvalidCursor);
        }
        let end = start
            .saturating_add(PAGE as usize)
            .min(operation.changes.len());
        let items:Vec<_>=operation.changes[start..end].iter().enumerate().map(|(i,c)|json!({
            "index":start+i,"oldPath":c.old_path,"newPath":c.new_path,"kind":change_kind(c),
            "contentIds":content_ids(c),"beforeKind":c.before.as_ref().map(|f|&f.kind),"afterKind":c.after.as_ref().map(|f|&f.kind)})).collect();
        return Ok(
            json!({"items":items,"next":(end<operation.changes.len()).then(||end.to_string())}),
        );
    }
    let change = operation
        .changes
        .get(query.file_index)
        .ok_or(history::Error::Invalid("file index out of range"))?;
    let ids = content_ids(change);
    if query
        .content_ids
        .as_ref()
        .is_some_and(|expected| expected != &ids)
    {
        return Err(history::Error::Conflict);
    }
    if method == Method::HistoryLocation {
        let workspace = store.get_workspace(session.workspace_id)?;
        return Ok(location(&workspace.worktree_path, change));
    }
    let (before, before_note) = text(&store, change.before.as_ref())?;
    let (after, after_note) = text(&store, change.after.as_ref())?;
    Ok(
        json!({"sessionId":query.session_id,"operationId":operation.input.id,"fileIndex":query.file_index,"contentIds":ids,
        "oldPath":change.old_path,"newPath":change.new_path,"before":before,"after":after,
        "beforeNote":before_note,"afterNote":after_note,
        "beforeMode":change.before.as_ref().map(|f|f.mode),"afterMode":change.after.as_ref().map(|f|f.mode)}),
    )
}
fn content_ids(change: &history::FileChange) -> HistoryContentIds {
    HistoryContentIds {
        before: change.before.as_ref().map(|f| f.blob.as_str().to_owned()),
        after: change.after.as_ref().map(|f| f.blob.as_str().to_owned()),
    }
}
fn change_kind(change: &history::FileChange) -> &'static str {
    match (&change.before, &change.after) {
        (None, _) => "added",
        (_, None) => "deleted",
        _ if change.old_path != change.new_path => "rename inferred from unique content match",
        _ => "modified",
    }
}
fn location(root: &std::path::Path, change: &history::FileChange) -> Value {
    use std::path::Component;
    let unavailable = |note| json!({"path":null,"workspace":root,"note":note});
    let Some(relative) = change.new_path.as_ref().or(change.old_path.as_ref()) else {
        return unavailable("No recorded path");
    };
    if root.canonicalize().ok().as_deref() != Some(root) {
        return unavailable(
            "Workspace missing, moved or redirected. Historical content remains independent.",
        );
    }
    if change
        .after
        .as_ref()
        .or(change.before.as_ref())
        .is_some_and(|f| f.kind == history::FileKind::Symlink)
    {
        return unavailable(
            "Recorded symlink target is available only in the historical diff; navigation does not follow it.",
        );
    }
    let mut target = root.to_path_buf();
    for part in std::path::Path::new(relative).components() {
        if !matches!(part, Component::Normal(_)) {
            return unavailable("Invalid recorded path");
        }
        target.push(part);
        match std::fs::symlink_metadata(&target) {
            Ok(meta) if !meta.file_type().is_symlink() => {}
            _ => {
                return unavailable(
                    "Recorded path is missing, moved or redirected. Select a later operation to locate a renamed file.",
                );
            }
        }
    }
    if !target.is_file() {
        return unavailable("Current location is not a regular file");
    }
    json!({"path":target,"workspace":root,"note":"Opening the CURRENT workspace file; it may differ from this historical operation."})
}
fn decode<T: serde::de::DeserializeOwned>(value: Value) -> history::Result<T> {
    serde_json::from_value(value).map_err(|_| history::Error::Invalid("invalid history query"))
}
pub(crate) fn text(
    store: &HistoryStore,
    entry: Option<&FileEntry>,
) -> history::Result<(Option<String>, &'static str)> {
    let Some(entry) = entry else {
        return Ok((Some(String::new()), "File absent"));
    };
    if entry.size > history::MAX_CHUNK_BYTES as u64 {
        return Ok((
            None,
            "Content exceeds the 64 KiB preview limit; immutable bytes retained",
        ));
    }
    let chunk = store.read_content(entry.blob.clone(), 0, history::MAX_CHUNK_BYTES)?;
    let value = String::from_utf8(chunk.bytes)
        .ok()
        .filter(|s| !s.contains('\0'));
    let note = if value.is_none() {
        "Binary or unsupported text encoding; immutable bytes retained (text previews require UTF-8)"
    } else if entry.kind == history::FileKind::Symlink {
        "Symlink target (not followed)"
    } else if value.as_ref().is_some_and(|s| s.contains("\r\n")) {
        "Recorded UTF-8 text, CRLF line endings"
    } else {
        "Recorded UTF-8 text"
    };
    Ok((value, note))
}

#[cfg(test)]
mod tests {
    use super::*;
    use history::{
        Attribution, Coverage, FileInput, FileKind, OperationInput, OperationStatus, Session,
        SnapshotInput, Turn, Workspace,
    };
    use protocol::{MutationKey, SessionId, WorkspaceId};
    use std::collections::BTreeMap;
    fn key(s: &str) -> MutationKey {
        MutationKey::try_from(s.to_owned()).unwrap()
    }

    #[test]
    fn history_queries_are_bounded_session_scoped_and_never_fabricate_missing_content() {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("project");
        let data = temp.path().join("data");
        std::fs::create_dir(&project).unwrap();
        std::fs::create_dir(project.join(".git")).unwrap();
        std::fs::create_dir(&data).unwrap();
        let store = HistoryStore::open(&project, &data).unwrap();
        let workspace = WorkspaceId::try_from("workspace".to_owned()).unwrap();
        store
            .register_workspace(
                key("workspace"),
                Workspace {
                    id: workspace.clone(),
                    worktree_path: project.clone(),
                    git_common_dir: project.join(".git"),
                    base_commit: "a".repeat(40),
                    owner_token: "fixture".into(),
                    lifecycle: "ready".into(),
                },
            )
            .unwrap();
        let capture = |files, time| SnapshotInput {
            workspace_id: workspace.clone(),
            files,
            coverage: Coverage {
                complete: true,
                excluded: vec![],
            },
            started_at_ms: time,
            completed_at_ms: time,
            provenance: "fixture".into(),
        };
        let before = store
            .publish_snapshot(key("before"), capture(BTreeMap::new(), 1))
            .unwrap()
            .outcome
            .unwrap()["id"]
            .as_str()
            .unwrap()
            .to_owned();
        for i in 0..21 {
            let name = format!("session-{i:02}");
            store
                .create_session(
                    key(&name),
                    Session {
                        id: SessionId::try_from(name).unwrap(),
                        workspace_id: workspace.clone(),
                        baseline: before.clone().try_into().unwrap(),
                        agent: "fixture".into(),
                        adapter_version: "1".into(),
                        upstream_id: None,
                        capabilities: json!({}),
                        status: "closed".into(),
                        created_at_ms: 1,
                    },
                )
                .unwrap();
        }
        let page = request(store.clone(), Method::SessionList, json!({})).unwrap();
        assert_eq!(page["items"].as_array().unwrap().len(), 20);
        assert_eq!(
            request(
                store.clone(),
                Method::SessionList,
                json!({"after":page["next"]})
            )
            .unwrap()["items"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        let session = SessionId::try_from("session-00".to_owned()).unwrap();
        let mut files: BTreeMap<_, _> = (0..21)
            .map(|i| {
                (
                    format!("file-{i:02}.txt"),
                    FileInput {
                        bytes: b"recorded\n".to_vec(),
                        mode: 0o644,
                        kind: FileKind::Regular,
                    },
                )
            })
            .collect();
        files.insert(
            "binary".into(),
            FileInput {
                bytes: vec![0, 255],
                mode: 0o644,
                kind: FileKind::Regular,
            },
        );
        files.insert(
            "large".into(),
            FileInput {
                bytes: vec![b'x'; history::MAX_CHUNK_BYTES + 1],
                mode: 0o644,
                kind: FileKind::Regular,
            },
        );
        let after = store
            .publish_snapshot(key("after"), capture(files, 2))
            .unwrap()
            .outcome
            .unwrap()["id"]
            .as_str()
            .unwrap()
            .to_owned();
        store
            .create_turn(
                key("turn"),
                Turn {
                    id: "turn".into(),
                    session_id: session.clone(),
                    prompt_provenance: "fixture".into(),
                    upstream_id: None,
                    status: "completed".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        store
            .record_operation(
                key("operation"),
                OperationInput {
                    id: "operation".into(),
                    session_id: session.clone(),
                    turn_id: "turn".into(),
                    before: before.try_into().unwrap(),
                    after: after.try_into().unwrap(),
                    tool_call_id: None,
                    parent_id: None,
                    status: OperationStatus::Completed,
                    attribution: Attribution::External,
                    evidence: "fixture".into(),
                    started_at_ms: 1,
                    completed_at_ms: 2,
                },
            )
            .unwrap();
        let query = json!({"sessionId":session,"operationId":"operation"});
        let page = request(store.clone(), Method::HistoryFiles, query.clone()).unwrap();
        assert_eq!(page["items"].as_array().unwrap().len(), 20);
        let mut next = query.clone();
        next["after"] = page["next"].clone();
        assert_eq!(
            request(store.clone(), Method::HistoryFiles, next).unwrap()["items"]
                .as_array()
                .unwrap()
                .len(),
            3
        );
        let binary = request(store.clone(), Method::HistoryDiff, query.clone()).unwrap();
        assert!(binary["after"].is_null());
        assert!(binary["afterNote"].as_str().unwrap().contains("Binary"));
        let mut large = query.clone();
        large["fileIndex"] = json!(22);
        assert!(request(store.clone(), Method::HistoryDiff, large).unwrap()["after"].is_null());
        let mut wrong = query.clone();
        wrong["sessionId"] = json!("session-01");
        assert!(request(store.clone(), Method::HistoryDiff, wrong).is_err());
        let mut invalid = query.clone();
        invalid["after"] = json!("18446744073709551616");
        assert!(request(store.clone(), Method::HistoryFiles, invalid).is_err());
        // Turn navigation is chronological, bounded, and session scoped.
        for i in 0..22 {
            store
                .create_turn(
                    key(&format!("extra-{i}")),
                    Turn {
                        id: format!("extra-{i:02}"),
                        session_id: session.clone(),
                        prompt_provenance: "fixture".into(),
                        upstream_id: None,
                        status: "completed".into(),
                        created_at_ms: 10 + i,
                    },
                )
                .unwrap();
        }
        let turns = request(
            store.clone(),
            Method::HistoryTurns,
            json!({"sessionId":session}),
        )
        .unwrap();
        assert_eq!(turns["items"].as_array().unwrap().len(), 20);
        assert_eq!(turns["items"][0]["id"], "turn");
        let next = request(
            store.clone(),
            Method::HistoryTurns,
            json!({"sessionId":session,"after":turns["next"]}),
        )
        .unwrap();
        assert_eq!(next["items"].as_array().unwrap().len(), 3);
        assert!(
            request(
                store.clone(),
                Method::HistoryTurns,
                json!({"sessionId":"session-01","after":turns["next"]})
            )
            .is_err()
        );
        let empty = request(
            store.clone(),
            Method::HistoryOperations,
            json!({"sessionId":session,"turnId":"extra-00"}),
        )
        .unwrap();
        assert!(empty["items"].as_array().unwrap().is_empty());
        let filtered = request(
            store.clone(),
            Method::HistoryOperations,
            json!({"sessionId":session,"turnId":"turn"}),
        )
        .unwrap();
        assert_eq!(filtered["items"].as_array().unwrap().len(), 1);
        assert_eq!(filtered["items"][0]["complete"], true);
        assert!(
            request(
                store.clone(),
                Method::HistoryOperations,
                json!({"sessionId":"session-01","turnId":"turn"})
            )
            .is_err()
        );
        store
            .agent_event(
                session.clone(),
                None,
                "closed".into(),
                "workspace.captureIncomplete".into(),
                json!({}),
            )
            .unwrap();
        assert_eq!(
            request(
                store.clone(),
                Method::HistoryTurns,
                json!({"sessionId":session})
            )
            .unwrap()["captureGap"],
            true
        );
        let mut mismatched = query.clone();
        mismatched["contentIds"] = json!({"before":null,"after":"0".repeat(64)});
        assert!(request(store.clone(), Method::HistoryDiff, mismatched).is_err());
        let absent = request(store.clone(), Method::HistoryLocation, query.clone()).unwrap();
        assert!(absent["path"].is_null());
        store.archive_session(session.clone()).unwrap();
        store.archive_session(session).unwrap();
        store.set_storage_budget(1).unwrap();
        let mut text = query.clone();
        text["fileIndex"] = json!(1);
        assert_eq!(
            request(store.clone(), Method::HistoryDiff, text.clone()).unwrap()["after"],
            "recorded\n"
        );
        let id = history::ContentId::hash(b"recorded\n");
        std::fs::remove_file(
            store
                .directory()
                .join("blobs")
                .join(format!("{}.zst", id.as_str())),
        )
        .unwrap();
        assert!(request(store, Method::HistoryDiff, text).is_err());
    }
    #[test]
    fn location_refuses_moved_files_and_symlinks_but_keeps_live_navigation_separate() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().canonicalize().unwrap();
        let file = FileEntry {
            blob: history::ContentId::hash(b"history"),
            size: 7,
            mode: 0o644,
            kind: FileKind::Regular,
        };
        let change = history::FileChange {
            old_path: Some("old.txt".into()),
            new_path: Some("new.txt".into()),
            before: Some(file.clone()),
            after: Some(file),
        };
        std::fs::write(root.join("new.txt"), b"different live bytes").unwrap();
        assert_eq!(
            location(&root, &change)["path"],
            json!(root.join("new.txt"))
        );
        assert!(
            location(&root, &change)["note"]
                .as_str()
                .unwrap()
                .contains("CURRENT")
        );
        std::fs::rename(root.join("new.txt"), root.join("later.txt")).unwrap();
        assert!(location(&root, &change)["path"].is_null());
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(root.join("later.txt"), root.join("new.txt")).unwrap();
            assert!(location(&root, &change)["path"].is_null());
            let redirected = root.join("redirected");
            std::os::unix::fs::symlink(&root, &redirected).unwrap();
            assert!(location(&redirected, &change)["path"].is_null());
        }
    }

    #[test]
    fn historical_rename_delete_and_encoding_survive_later_changes_and_store_restart() {
        let tmp = tempfile::tempdir().unwrap();
        let project = tmp.path().join("project");
        let data = tmp.path().join("data");
        std::fs::create_dir_all(project.join(".git")).unwrap();
        std::fs::create_dir(&data).unwrap();
        let store = HistoryStore::open(&project, &data).unwrap();
        let workspace = WorkspaceId::try_from("workspace".to_owned()).unwrap();
        store
            .register_workspace(
                key("workspace"),
                Workspace {
                    id: workspace.clone(),
                    worktree_path: project.canonicalize().unwrap(),
                    git_common_dir: project.join(".git"),
                    base_commit: "a".repeat(40),
                    owner_token: "fixture".into(),
                    lifecycle: "ready".into(),
                },
            )
            .unwrap();
        let make = |name: &str, path: &str, bytes: &[u8], complete: bool| {
            let files = if path.is_empty() {
                BTreeMap::new()
            } else {
                BTreeMap::from([(
                    path.into(),
                    FileInput {
                        bytes: bytes.to_vec(),
                        mode: 0o644,
                        kind: FileKind::Regular,
                    },
                )])
            };
            let id = store
                .publish_snapshot(
                    key(name),
                    SnapshotInput {
                        workspace_id: workspace.clone(),
                        files,
                        coverage: Coverage {
                            complete,
                            excluded: if complete {
                                vec![]
                            } else {
                                vec!["excluded".into()]
                            },
                        },
                        started_at_ms: 1,
                        completed_at_ms: 2,
                        provenance: name.into(),
                    },
                )
                .unwrap()
                .outcome
                .unwrap()["id"]
                .as_str()
                .unwrap()
                .to_owned();
            history::ContentId::try_from(id).unwrap()
        };
        let initial = make("initial", "old.txt", b"one\r\n", true);
        let renamed = make("renamed", "new.txt", b"one\r\n", true);
        let deleted = make("deleted", "", b"", true);
        let encoded = make("encoded", "utf16.txt", &[255, 254, 97, 0], false);
        let session = SessionId::try_from("session".to_owned()).unwrap();
        store
            .create_session(
                key("session"),
                Session {
                    id: session.clone(),
                    workspace_id: workspace,
                    baseline: initial.clone(),
                    agent: "fixture".into(),
                    adapter_version: "1".into(),
                    upstream_id: None,
                    capabilities: json!({}),
                    status: "closed".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        store
            .create_turn(
                key("turn"),
                Turn {
                    id: "turn".into(),
                    session_id: session.clone(),
                    prompt_provenance: "fixture".into(),
                    upstream_id: None,
                    status: "completed".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        for (id, before, after) in [
            ("rename", initial, renamed.clone()),
            ("delete", renamed, deleted.clone()),
            ("encoded", deleted, encoded),
        ] {
            store
                .record_operation(
                    key(&format!("op-{id}")),
                    OperationInput {
                        id: id.into(),
                        session_id: session.clone(),
                        turn_id: "turn".into(),
                        before,
                        after,
                        tool_call_id: None,
                        parent_id: None,
                        status: OperationStatus::Completed,
                        attribution: Attribution::CheckpointInterval,
                        evidence: "Observed interval".into(),
                        started_at_ms: 1,
                        completed_at_ms: 2,
                    },
                )
                .unwrap();
        }
        let files = request(
            store.clone(),
            Method::HistoryFiles,
            json!({"sessionId":session,"operationId":"rename"}),
        )
        .unwrap();
        assert_eq!(files["items"][0]["oldPath"], "old.txt");
        assert_eq!(files["items"][0]["newPath"], "new.txt");
        assert!(
            files["items"][0]["kind"]
                .as_str()
                .unwrap()
                .contains("inferred")
        );
        let query = json!({"sessionId":session,"operationId":"rename","contentIds":files["items"][0]["contentIds"]});
        let historical = request(store.clone(), Method::HistoryDiff, query.clone()).unwrap();
        assert_eq!(historical["before"], "one\r\n");
        assert_eq!(historical["after"], "one\r\n");
        assert!(historical["beforeNote"].as_str().unwrap().contains("CRLF"));
        std::fs::write(project.join("new.txt"), b"later user content").unwrap();
        std::fs::remove_file(project.join("new.txt")).unwrap();
        drop(store);
        let store = HistoryStore::open(&project, &data).unwrap();
        assert_eq!(
            request(store.clone(), Method::HistoryDiff, query).unwrap(),
            historical
        );
        let deleted = request(
            store.clone(),
            Method::HistoryDiff,
            json!({"sessionId":session,"operationId":"delete"}),
        )
        .unwrap();
        assert_eq!(deleted["before"], "one\r\n");
        assert_eq!(deleted["after"], "");
        assert!(deleted["contentIds"]["after"].is_null());
        let encoded = request(
            store.clone(),
            Method::HistoryDiff,
            json!({"sessionId":session,"operationId":"encoded"}),
        )
        .unwrap();
        assert!(encoded["after"].is_null());
        assert!(encoded["afterNote"].as_str().unwrap().contains("encoding"));
        let operations = request(
            store,
            Method::HistoryOperations,
            json!({"sessionId":session}),
        )
        .unwrap();
        assert_eq!(operations["items"][2]["complete"], false);
    }
}
