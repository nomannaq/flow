use super::*;
use std::process::Command;

struct Fixture {
    _temp: tempfile::TempDir,
    project: PathBuf,
    data: PathBuf,
}
impl Fixture {
    fn new() -> Self {
        let temp = tempfile::tempdir().unwrap();
        let project = temp.path().join("repository with spaces");
        let data = temp.path().join("data");
        fs::create_dir(&project).unwrap();
        fs::create_dir(project.join(".git")).unwrap();
        fs::create_dir(&data).unwrap();
        Self {
            _temp: temp,
            project,
            data,
        }
    }
    fn open(&self) -> HistoryStore {
        HistoryStore::open(&self.project, &self.data).unwrap()
    }
    fn register(&self, store: &HistoryStore) {
        store
            .register_workspace(key("workspace"), workspace(&self.project))
            .unwrap();
    }
}
fn key(s: &str) -> MutationKey {
    MutationKey::try_from(s.to_owned()).unwrap()
}

#[test]
fn recorder_publication_failure_keeps_last_checkpoint_and_requires_recovery() {
    let fixture = Fixture::new();
    let store = fixture.open();
    fixture.register(&store);
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("baseline"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(&store, baseline.clone());
    let mut recorder = Recorder::new(store.clone(), sid()).unwrap();
    let observation = || Observation {
        turn_id: "missing-turn".into(),
        status: OperationStatus::Completed,
        attribution: Attribution::CheckpointInterval,
        tool_call_id: None,
        evidence: "fixture".into(),
    };
    assert!(
        recorder
            .observe(capture(b"changed", 2), observation())
            .is_err()
    );
    assert_eq!(recorder.checkpoint().id, baseline);
    assert!(store.operations(sid(), 0, 100).unwrap().items.is_empty());
    assert!(matches!(
        recorder.observe(capture(b"changed", 3), observation()),
        Err(Error::Invalid("recorder requires recovery"))
    ));
}

#[test]
fn agent_recovery_interrupts_live_records_without_replaying_permissions() {
    let fixture = Fixture::new();
    let store = fixture.open();
    fixture.register(&store);
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("capture"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(&store, baseline);
    store
        .bind_agent(
            sid(),
            "native-session".into(),
            "1.2".into(),
            value!({"loadSession":true}),
        )
        .unwrap();
    store
        .agent_event(
            sid(),
            Some("turn-1".into()),
            "running".into(),
            "permission.requested".into(),
            value!({"requestId":"expired"}),
        )
        .unwrap();
    drop(store);
    let reopened = fixture.open();
    reopened.interrupt_agent_sessions().unwrap();
    assert_eq!(reopened.get_session(sid()).unwrap().status, "interrupted");
    assert_eq!(
        reopened.get_session(sid()).unwrap().upstream_id.as_deref(),
        Some("native-session")
    );
    assert_eq!(
        reopened.get_turn("turn-1".into()).unwrap().status,
        "interrupted"
    );
    let first = reopened.snapshot().unwrap().high_water;
    reopened.interrupt_agent_sessions().unwrap();
    assert_eq!(reopened.snapshot().unwrap().high_water, first);
}
fn ws() -> WorkspaceId {
    WorkspaceId::try_from("worktree-1".to_owned()).unwrap()
}
fn sid() -> SessionId {
    SessionId::try_from("session-1".to_owned()).unwrap()
}
fn workspace(project: &Path) -> Workspace {
    Workspace {
        id: ws(),
        worktree_path: project.to_owned(),
        git_common_dir: project.join(".git"),
        base_commit: "a".repeat(40),
        owner_token: "owner-1".into(),
        lifecycle: "ready".into(),
    }
}
fn capture(bytes: &[u8], time: u64) -> SnapshotInput {
    SnapshotInput {
        workspace_id: ws(),
        files: BTreeMap::from([(
            "src/日本語.txt".into(),
            FileInput {
                bytes: bytes.to_vec(),
                mode: 0o644,
                kind: FileKind::Regular,
            },
        )]),
        coverage: Coverage {
            complete: true,
            excluded: vec![],
        },
        started_at_ms: time,
        completed_at_ms: time,
        provenance: "fixture-checkpoint".into(),
    }
}
fn snapshot_id(receipt: MutationReceipt) -> ContentId {
    serde_json::from_value(receipt.outcome.unwrap()["id"].clone()).unwrap()
}
fn make_session(store: &HistoryStore, baseline: ContentId) {
    store
        .create_session(
            key("session"),
            Session {
                id: sid(),
                workspace_id: ws(),
                baseline,
                agent: "fixture".into(),
                adapter_version: "1".into(),
                upstream_id: None,
                capabilities: value!({}),
                status: "running".into(),
                created_at_ms: 1,
            },
        )
        .unwrap();
    store
        .create_turn(
            key("turn"),
            Turn {
                id: "turn-1".into(),
                session_id: sid(),
                prompt_provenance: "fixture".into(),
                upstream_id: None,
                status: "running".into(),
                created_at_ms: 1,
            },
        )
        .unwrap();
}
fn op(before: ContentId, after: ContentId) -> OperationInput {
    OperationInput {
        id: "op-1".into(),
        session_id: sid(),
        turn_id: "turn-1".into(),
        before,
        after,
        tool_call_id: None,
        parent_id: None,
        status: OperationStatus::Completed,
        attribution: Attribution::CheckpointInterval,
        evidence: "verified checkpoints; no exact tool attribution".into(),
        started_at_ms: 1,
        completed_at_ms: 2,
    }
}

#[test]
fn round_trips_deduplicates_pages_and_reopens_without_touching_repository() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let mut input = capture(b"line\r\n\0", 1);
    input.files.insert(
        "empty".into(),
        FileInput {
            bytes: vec![],
            mode: 0o600,
            kind: FileKind::Regular,
        },
    );
    input.files.insert(
        "binary".into(),
        FileInput {
            bytes: vec![0, 255, 128, 1],
            mode: 0o755,
            kind: FileKind::Regular,
        },
    );
    input
        .files
        .insert("same bytes".into(), input.files["src/日本語.txt"].clone());
    let first = snapshot_id(
        store
            .publish_snapshot(key("capture-1"), input.clone())
            .unwrap(),
    );
    let receipt = store
        .publish_snapshot(key("capture-1"), input.clone())
        .unwrap();
    assert_eq!(snapshot_id(receipt.clone()), first);
    let high = store.snapshot().unwrap().high_water;
    let again = snapshot_id(
        store
            .publish_snapshot(key("capture-copy"), input.clone())
            .unwrap(),
    );
    assert_eq!(again, first);
    assert_eq!(store.snapshot().unwrap().high_water, high);
    let second = snapshot_id(
        store
            .publish_snapshot(key("capture-2"), capture(b"second", 2))
            .unwrap(),
    );
    let page = store.snapshots(ws(), 0, 1).unwrap();
    assert_eq!(page.items[0].id, first);
    let next = store.snapshots(ws(), page.next.unwrap(), 1).unwrap();
    assert_eq!(next.items[0].id, second);
    assert!(next.next.is_none());
    let directory = store.directory().to_owned();
    drop(store);
    let store = f.open();
    assert_eq!(
        store
            .mutation(&key("capture-1"))
            .unwrap()
            .unwrap()
            .request_digest,
        receipt.request_digest
    );
    let saved = store.get_snapshot(first).unwrap();
    for (name, file) in input.files {
        let entry = &saved.manifest.files[&name];
        let mut offset = 0;
        let mut reconstructed = Vec::new();
        loop {
            let chunk = store.read_content(entry.blob.clone(), offset, 2).unwrap();
            reconstructed.extend(chunk.bytes);
            match chunk.next_offset {
                Some(next) => offset = next,
                None => break,
            }
        }
        assert_eq!(reconstructed, file.bytes);
        assert_eq!(entry.mode, file.mode);
    }
    assert_eq!(fs::read_dir(directory.join("blobs")).unwrap().count(), 4);
    assert_eq!(fs::read_dir(&f.project).unwrap().count(), 1);
    assert_eq!(store.get_workspace(ws()).unwrap().owner_token, "owner-1");
}

#[test]
fn operations_preserve_type_mode_changes_and_honest_partial_coverage() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let before = snapshot_id(
        store
            .publish_snapshot(key("a"), capture(b"same", 1))
            .unwrap(),
    );
    make_session(&store, before.clone());
    let mut input = capture(b"same", 2);
    input.files.get_mut("src/日本語.txt").unwrap().mode = 0o755;
    input.files.get_mut("src/日本語.txt").unwrap().kind = FileKind::Symlink;
    input.files.insert(
        "added".into(),
        FileInput {
            bytes: b"new".to_vec(),
            mode: 0o644,
            kind: FileKind::Regular,
        },
    );
    let after = snapshot_id(store.publish_snapshot(key("b"), input).unwrap());
    store
        .record_operation(key("op"), op(before.clone(), after.clone()))
        .unwrap();
    let recorded = store.get_operation("op-1".into()).unwrap();
    assert_eq!(recorded.sequence, 1);
    assert_eq!(recorded.changes.len(), 2);
    let change = &recorded.changes[1];
    assert_eq!(
        change.before.as_ref().unwrap().blob,
        change.after.as_ref().unwrap().blob
    );
    assert_ne!(
        change.before.as_ref().unwrap().mode,
        change.after.as_ref().unwrap().mode
    );
    assert_ne!(
        change.before.as_ref().unwrap().kind,
        change.after.as_ref().unwrap().kind
    );
    assert_eq!(store.operations(sid(), 0, 1).unwrap().items.len(), 1);
    assert_eq!(store.get_session(sid()).unwrap().baseline, before);
    assert_eq!(store.workspaces(None, 1).unwrap().items[0].id, ws());
    assert!(store.workspaces(Some(ws()), 1).unwrap().items.is_empty());
    assert_eq!(store.sessions(ws(), None, 1).unwrap().items[0].id, sid());
    assert!(
        store
            .sessions(ws(), Some(sid()), 1)
            .unwrap()
            .items
            .is_empty()
    );
    assert_eq!(store.get_turn("turn-1".into()).unwrap().session_id, sid());
    let mut partial = capture(b"same", 3);
    partial.files.clear();
    partial.coverage = Coverage {
        complete: false,
        excluded: vec!["src".into()],
    };
    let partial = snapshot_id(store.publish_snapshot(key("partial"), partial).unwrap());
    let mut unknown = op(after.clone(), partial);
    unknown.id = "op-2".into();
    unknown.status = OperationStatus::Failed;
    store.record_operation(key("unknown"), unknown).unwrap();
    assert!(
        store
            .get_operation("op-2".into())
            .unwrap()
            .changes
            .is_empty()
    );
    let page = store.operations(sid(), 0, 1).unwrap();
    assert_eq!(page.next, Some(1));
    assert_eq!(store.operations(sid(), 1, 1).unwrap().items[0].id, "op-2");
    let mut empty = capture(b"", 4);
    empty.files.clear();
    let empty = snapshot_id(store.publish_snapshot(key("empty"), empty).unwrap());
    let mut deletion = op(after, empty);
    deletion.id = "op-3".into();
    store.record_operation(key("delete"), deletion).unwrap();
    let deleted = store.get_operation("op-3".into()).unwrap();
    assert_eq!(deleted.changes.len(), 2);
    assert!(
        deleted
            .changes
            .iter()
            .all(|c| c.before.is_some() && c.after.is_none() && c.new_path.is_none())
    );
}

#[test]
fn mutation_receipts_replay_and_project_isolation() {
    let f = Fixture::new();
    let mut store = f.open();
    f.register(&store);
    let zero = EventCursor {
        store_id: store.snapshot().unwrap().high_water.store_id,
        sequence: EventSequence(0),
    };
    store.publish_snapshot(key("a"), capture(b"a", 1)).unwrap();
    assert!(matches!(
        store.publish_snapshot(key("a"), capture(b"different", 1)),
        Err(Error::Conflict)
    ));
    assert_eq!(store.snapshot().unwrap().state["snapshots"], 1);
    let page = store
        .replay(&ReplayParams {
            after: zero.clone(),
            limit: 1,
        })
        .unwrap();
    assert_eq!(page.events.len(), 1);
    assert!(page.has_more);
    let second = store
        .replay(&ReplayParams {
            after: page.events[0].cursor.clone(),
            limit: 1,
        })
        .unwrap();
    assert_eq!(second.events[0].kind, "snapshot.published");
    assert!(!second.has_more);
    let mut foreign = zero;
    foreign.store_id = StoreId::try_from("foreign".to_owned()).unwrap();
    assert!(matches!(
        store.replay(&ReplayParams {
            after: foreign,
            limit: 1
        }),
        Err(Error::InvalidCursor)
    ));
    let digest = ContentId::hash(b"utility");
    let mut receipt = store
        .claim_mutation(&key("utility"), digest.as_str())
        .unwrap();
    assert_eq!(receipt.status, MutationStatus::Accepted);
    receipt.status = MutationStatus::Succeeded;
    receipt.outcome = Some(value!({"ok":true}));
    store.finish_mutation(&receipt).unwrap();
    store.finish_mutation(&receipt).unwrap();
    receipt.status = MutationStatus::Running;
    assert!(matches!(
        store.finish_mutation(&receipt),
        Err(Error::Conflict)
    ));
    assert!(matches!(
        store.claim_mutation(&key("utility"), ContentId::hash(b"wrong").as_str()),
        Err(Error::Conflict)
    ));
    let other = f._temp.path().join("other-project");
    fs::create_dir(&other).unwrap();
    let other = HistoryStore::open(&other, &f.data).unwrap();
    assert_ne!(other.directory(), store.directory());
    assert_eq!(other.snapshot().unwrap().state["snapshots"], 0);
}

#[test]
fn missing_corrupt_and_unreferenced_objects_are_distinguished() {
    for corrupt in [false, true] {
        let f = Fixture::new();
        let store = f.open();
        f.register(&store);
        let id = snapshot_id(
            store
                .publish_snapshot(key("a"), capture(b"original", 1))
                .unwrap(),
        );
        let blob = store.get_snapshot(id).unwrap().manifest.files["src/日本語.txt"]
            .blob
            .clone();
        let path = store
            .directory()
            .join("blobs")
            .join(format!("{}.zst", blob.as_str()));
        drop(store);
        if corrupt {
            fs::write(&path, b"truncated frame").unwrap();
        } else {
            fs::remove_file(&path).unwrap();
        }
        let reopened = HistoryStore::open(&f.project, &f.data).unwrap();
        assert!(!reopened.storage_report().unwrap().writable);
        assert_eq!(reopened.snapshot().unwrap().state["snapshots"], 1);
        assert!(matches!(
            reopened.publish_snapshot(key("blocked"), capture(b"new", 2)),
            Err(Error::ReadOnly)
        ));
        let err = reopened.read_content(blob, 0, 100).err().unwrap();
        assert!(if corrupt {
            matches!(err, Error::CorruptContent(_))
        } else {
            matches!(err, Error::MissingContent(_))
        });
    }
    let f = Fixture::new();
    let store = f.open();
    let root = store.directory().join("blobs");
    drop(store);
    fs::write(root.join("unreferenced.tmp"), b"orphan").unwrap();
    f.open(); // Unreferenced files are retained, never fabricated into snapshots or deleted.
    assert!(root.join("unreferenced.tmp").exists());
}

#[test]
fn validates_ownership_paths_limits_and_single_writer() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    assert!(matches!(
        HistoryStore::open(&f.project, &f.data),
        Err(Error::Locked)
    ));
    let inside = f.project.join("history");
    fs::create_dir(&inside).unwrap();
    assert!(matches!(
        HistoryStore::open(&f.project, &inside),
        Err(Error::Invalid(_))
    ));
    let bare = f._temp.path().join("bare.git");
    fs::create_dir(&bare).unwrap();
    fs::create_dir(bare.join("objects")).unwrap();
    fs::create_dir(bare.join("refs")).unwrap();
    fs::write(bare.join("HEAD"), b"ref: refs/heads/main\n").unwrap();
    assert!(matches!(
        HistoryStore::open(&f.project, &bare),
        Err(Error::Invalid(_))
    ));
    for path in [
        "../escape",
        "/absolute",
        "a/../b",
        "a\\b",
        "a//b",
        "C:/escape",
    ] {
        let mut bad = capture(b"data", 1);
        let file = bad.files.pop_first().unwrap().1;
        bad.files.insert(path.into(), file);
        assert!(matches!(
            store.publish_snapshot(key("bad"), bad),
            Err(Error::Invalid(_))
        ));
    }
    let mut bad = capture(b"data", 1);
    bad.files.insert(
        "src".into(),
        FileInput {
            bytes: vec![],
            mode: 0,
            kind: FileKind::Symlink,
        },
    );
    assert!(matches!(
        store.publish_snapshot(key("bad"), bad),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        store.snapshots(ws(), 0, 0),
        Err(Error::Invalid(_))
    ));
    assert!(matches!(
        store.read_content(ContentId::hash(b"a"), 0, MAX_CHUNK_BYTES + 1),
        Err(Error::Invalid(_))
    ));
    assert_eq!(store.snapshot().unwrap().state["snapshots"], 0);
}

#[test]
fn migrates_with_consistent_backup_and_rejects_newer_schema() {
    let f = Fixture::new();
    let store = f.open();
    let dir = store.directory().to_owned();
    drop(store);
    let path = dir.join("history.sqlite3");
    let old = Connection::open(&path).unwrap();
    old.execute_batch("DROP TABLE candidate_checks; DROP TABLE candidates; DROP TABLE events; DROP TABLE mutations; DROP TABLE storage_policy; DROP TABLE history_holds; DROP TABLE deletions; PRAGMA user_version=1;")
        .unwrap();
    old.execute(
        "INSERT INTO workspaces VALUES('legacy','/legacy',?1)",
        [json(&workspace(&f.project)).unwrap()],
    )
    .unwrap();
    // Keep the WAL connection open while the migration takes its backup.
    let store = f.open();
    let backups: Vec<_> = fs::read_dir(&dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| {
            p.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("migration-v1-")
        })
        .collect();
    assert_eq!(backups.len(), 1);
    let backup = Connection::open(&backups[0]).unwrap();
    assert_eq!(
        backup
            .pragma_query_value(None, "user_version", |r| r.get::<_, u32>(0))
            .unwrap(),
        1
    );
    assert_eq!(
        backup
            .query_row("SELECT COUNT(*) FROM workspaces", [], |r| r
                .get::<_, i64>(0))
            .unwrap(),
        1
    );
    drop(backup);
    drop(old);
    drop(store);
    let conn = Connection::open(&path).unwrap();
    conn.pragma_update(None, "user_version", 999).unwrap();
    drop(conn);
    assert!(matches!(
        HistoryStore::open(&f.project, &f.data),
        Err(Error::UnsupportedSchema(999))
    ));
}

#[test]
fn publication_crashes_recover_without_phantom_success() {
    for stage in ["blob", "before-commit", "after-commit"] {
        let f = Fixture::new();
        let output = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "store::tests::crash_child", "--nocapture"])
            .env("FLOW_B04_CRASH_STAGE", stage)
            .env("FLOW_B04_CRASH_ROOT", f._temp.path())
            .output()
            .unwrap();
        assert_eq!(
            output.status.code(),
            Some(86),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let store = f.open();
        let committed = stage == "after-commit";
        assert_eq!(
            store.snapshot().unwrap().state["snapshots"],
            u64::from(committed)
        );
        assert_eq!(
            store.mutation(&key("capture")).unwrap().is_some(),
            committed
        );
        let before = store.snapshot().unwrap().high_water.sequence.0;
        let receipt = store
            .publish_snapshot(key("capture"), capture(b"survives", 1))
            .unwrap();
        let id = snapshot_id(receipt);
        let manifest = store.get_snapshot(id).unwrap();
        assert_eq!(
            store
                .read_content(
                    manifest.manifest.files["src/日本語.txt"].blob.clone(),
                    0,
                    100
                )
                .unwrap()
                .bytes,
            b"survives"
        );
        assert_eq!(
            store.snapshot().unwrap().high_water.sequence.0,
            before + u64::from(!committed)
        );
    }
}

#[test]
fn crash_child() {
    let Ok(stage) = std::env::var("FLOW_B04_CRASH_STAGE") else {
        return;
    };
    let root = PathBuf::from(std::env::var_os("FLOW_B04_CRASH_ROOT").unwrap());
    let project = root.join("repository with spaces");
    let store = HistoryStore::open(&project, &root.join("data")).unwrap();
    store
        .register_workspace(key("workspace"), workspace(&project))
        .unwrap();
    store
        .call(move |db| {
            db.crash = Some(match stage.as_str() {
                "blob" => Boundary::BlobPublished,
                "before-commit" => Boundary::BeforeCommit,
                _ => Boundary::AfterCommit,
            });
            Ok(())
        })
        .unwrap();
    store
        .publish_snapshot(key("capture"), capture(b"survives", 1))
        .unwrap();
    panic!("crash hook did not run");
}

#[test]
fn bounded_writer_serializes_duplicate_mutations() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let (entered_tx, entered_rx) = mpsc::sync_channel(1);
    let (release_tx, release_rx) = mpsc::sync_channel(1);
    let sender = store.worker.sender.as_ref().unwrap();
    assert!(
        sender
            .try_send(Box::new(move |_| {
                entered_tx.send(()).unwrap();
                release_rx.recv().unwrap();
            }))
            .is_ok()
    );
    entered_rx.recv().unwrap();
    let (drained_tx, drained_rx) = mpsc::sync_channel(QUEUE_CAPACITY);
    for _ in 0..QUEUE_CAPACITY {
        let tx = drained_tx.clone();
        assert!(
            sender
                .try_send(Box::new(move |_| {
                    tx.send(()).unwrap();
                }))
                .is_ok()
        );
    }
    assert!(matches!(store.snapshot(), Err(Error::Busy)));
    release_tx.send(()).unwrap();
    for _ in 0..QUEUE_CAPACITY {
        drained_rx.recv().unwrap();
    }
    let threads: Vec<_> = (0..4)
        .map(|_| {
            let store = store.clone();
            thread::spawn(move || {
                store
                    .publish_snapshot(key("same"), capture(b"same", 1))
                    .unwrap()
            })
        })
        .collect();
    for thread in threads {
        assert_eq!(thread.join().unwrap().status, MutationStatus::Succeeded);
    }
    assert_eq!(store.snapshot().unwrap().state["snapshots"], 1);
    assert_eq!(store.snapshot().unwrap().high_water.sequence.0, 2);
}

#[test]
fn database_failure_rolls_back_metadata_events_and_receipt() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let before = store.snapshot().unwrap();
    let mut bad = capture(b"orphan allowed", 1);
    bad.workspace_id = WorkspaceId::try_from("unregistered".to_owned()).unwrap();
    assert!(store.publish_snapshot(key("failed"), bad).is_err());
    let mut after_state = store.snapshot().unwrap().state;
    let mut before_state = before.state;
    assert!(
        after_state["history"]["bytes"].as_u64().unwrap()
            >= before_state["history"]["bytes"].as_u64().unwrap()
    );
    after_state.as_object_mut().unwrap().remove("history");
    before_state.as_object_mut().unwrap().remove("history");
    assert_eq!(after_state, before_state);
    assert_eq!(store.snapshot().unwrap().high_water, before.high_water);
    assert!(store.mutation(&key("failed")).unwrap().is_none());
    let id = snapshot_id(
        store
            .publish_snapshot(key("good"), capture(b"base", 1))
            .unwrap(),
    );
    let mut session = Session {
        id: sid(),
        workspace_id: WorkspaceId::try_from("different".to_owned()).unwrap(),
        baseline: id.clone(),
        agent: "fixture".into(),
        adapter_version: "1".into(),
        upstream_id: None,
        capabilities: value!({}),
        status: "running".into(),
        created_at_ms: 1,
    };
    assert!(matches!(
        store.create_session(key("wrong-workspace"), session.clone()),
        Err(Error::Conflict)
    ));
    session.workspace_id = ws();
    store
        .create_session(key("right-workspace"), session)
        .unwrap();
    assert!(store.mutation(&key("wrong-workspace")).unwrap().is_none());
    let directory = store.directory().to_owned();
    drop(store);
    let conn = Connection::open(directory.join("history.sqlite3")).unwrap();
    conn.execute(
        "UPDATE snapshots SET manifest='{}' WHERE id=?1",
        [id.as_str()],
    )
    .unwrap();
    drop(conn);
    let damaged = HistoryStore::open(&f.project, &f.data).unwrap();
    assert!(!damaged.storage_report().unwrap().writable);
    assert!(matches!(
        damaged.get_snapshot(id),
        Err(Error::CorruptMetadata)
    ));
}

#[cfg(unix)]
#[test]
fn content_symlinks_are_never_followed() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let id = snapshot_id(
        store
            .publish_snapshot(key("a"), capture(b"bytes", 1))
            .unwrap(),
    );
    let blob = store.get_snapshot(id).unwrap().manifest.files["src/日本語.txt"]
        .blob
        .clone();
    let path = store
        .directory()
        .join("blobs")
        .join(format!("{}.zst", blob.as_str()));
    let external = f._temp.path().join("outside");
    fs::write(&external, b"do not read or replace").unwrap();
    fs::remove_file(&path).unwrap();
    std::os::unix::fs::symlink(&external, &path).unwrap();
    assert!(matches!(
        store.read_content(blob, 0, 100),
        Err(Error::CorruptContent(_))
    ));
    assert_eq!(fs::read(&external).unwrap(), b"do not read or replace");
    let lock = store.directory().join("writer.lock");
    drop(store);
    fs::remove_file(&lock).unwrap();
    let missing = f._temp.path().join("must-not-create");
    std::os::unix::fs::symlink(&missing, &lock).unwrap();
    assert!(matches!(
        HistoryStore::open(&f.project, &f.data),
        Err(Error::Invalid(_))
    ));
    assert!(!missing.exists());
}

#[test]
fn storage_budget_stops_publication_without_losing_readable_history() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("base"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(&store, baseline.clone());
    let bytes = store.storage_report().unwrap().bytes;
    store.set_storage_budget(bytes).unwrap();
    let count = fs::read_dir(store.directory().join("blobs"))
        .unwrap()
        .count();
    assert!(matches!(
        store.publish_snapshot(key("overflow"), capture(b"new", 2)),
        Err(Error::BudgetExceeded)
    ));
    assert_eq!(
        count,
        fs::read_dir(store.directory().join("blobs"))
            .unwrap()
            .count()
    );
    assert!(store.get_snapshot(baseline.clone()).is_ok());
    assert!(!store.storage_report().unwrap().writable);
    drop(store);
    let store = f.open();
    assert!(!store.storage_report().unwrap().writable);
    store.set_storage_budget(1024 * 1024 * 1024).unwrap();
    assert!(store.storage_report().unwrap().writable);
    assert_eq!(store.checkpoint(sid()).unwrap().id, baseline);
}

#[test]
fn unresolved_mutations_and_holds_require_explicit_recovery_handlers() {
    let f = Fixture::new();
    let mut store = f.open();
    f.register(&store);
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("base"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(&store, baseline);
    let receipt = store
        .claim_mutation(&key("external-write"), &"a".repeat(64))
        .unwrap();
    store
        .hold_history(
            "apply-journal".into(),
            sid(),
            "external mutation needs reconciliation".into(),
        )
        .unwrap();
    drop(store);
    let store = f.open();
    assert!(!store.storage_report().unwrap().writable);
    assert!(matches!(
        store.publish_snapshot(key("blocked"), capture(b"new", 2)),
        Err(Error::ReadOnly)
    ));
    let mut terminal = receipt;
    terminal.status = MutationStatus::Failed;
    terminal.outcome = Some(value!({"verified":"not applied"}));
    store.resolve_interrupted_mutation(terminal).unwrap();
    assert!(!store.storage_report().unwrap().writable); // active recovery hold still gates writes
    store.release_history("apply-journal".into()).unwrap();
    assert!(store.storage_report().unwrap().writable);
}

fn retention_fixture(store: &HistoryStore) -> (ContentId, ContentId) {
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("base"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(store, baseline.clone());
    let mut recorder = Recorder::new(store.clone(), sid()).unwrap();
    recorder
        .observe(
            capture(b"only selected session", 2),
            Observation {
                turn_id: "turn-1".into(),
                status: OperationStatus::Completed,
                attribution: Attribution::CheckpointInterval,
                tool_call_id: None,
                evidence: "fixture".into(),
            },
        )
        .unwrap();
    let blob = recorder.checkpoint().manifest.files["src/日本語.txt"]
        .blob
        .clone();
    store
        .agent_event(
            sid(),
            Some("turn-1".into()),
            "closed".into(),
            "session.closed".into(),
            value!({}),
        )
        .unwrap();
    (baseline, blob)
}

#[test]
fn explicit_retention_protects_holds_shared_content_and_replay_identity() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let (baseline, blob) = retention_fixture(&store);
    store
        .hold_history("review".into(), sid(), "open historical review".into())
        .unwrap();
    assert!(store.delete_closed_session(sid(), ws()).is_err());
    store.release_history("review".into()).unwrap();
    let cursor = store.snapshot().unwrap().high_water;
    // Budget exhaustion must still permit explicit retention maintenance.
    store.set_storage_budget(1).unwrap();
    store.delete_closed_session(sid(), ws()).unwrap();
    assert!(matches!(store.get_session(sid()), Err(Error::NotFound)));
    assert!(store.get_snapshot(baseline).is_ok());
    assert!(
        !store
            .directory()
            .join("blobs")
            .join(format!("{}.zst", blob.as_str()))
            .exists()
    );
    assert_ne!(
        store.snapshot().unwrap().high_water.store_id,
        cursor.store_id
    );
    assert!(matches!(
        store.replay(&ReplayParams {
            after: cursor,
            limit: 100
        }),
        Err(Error::InvalidCursor)
    ));
    assert_eq!(store.storage_report().unwrap().pending_deletions, 0);
    store.finish_retention().unwrap();
}

#[test]
fn retention_rejects_active_and_shared_workspace_sessions() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let (baseline, _) = retention_fixture(&store);
    let mut other = store.get_session(sid()).unwrap();
    other.id = SessionId::try_from("another-session".to_owned()).unwrap();
    store.create_session(key("another-session"), other).unwrap();
    assert!(store.delete_closed_session(sid(), ws()).is_err());
    store
        .agent_event(
            sid(),
            None,
            "ready".into(),
            "session.ready".into(),
            value!({}),
        )
        .unwrap();
    assert!(store.delete_closed_session(sid(), ws()).is_err());
    assert!(store.get_snapshot(baseline).is_ok());
}

#[test]
fn recovery_uses_operation_commit_not_unlinked_snapshot() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let (baseline, _) = retention_fixture(&store);
    let committed = store.checkpoint(sid()).unwrap().id;
    assert_ne!(baseline, committed);
    store
        .publish_snapshot(key("unlinked"), capture(b"abandoned", 3))
        .unwrap();
    drop(store);
    let store = f.open();
    let mut recorder = Recorder::recover(store.clone(), sid()).unwrap();
    assert_eq!(recorder.checkpoint().id, committed);
    recorder
        .observe(
            capture(b"current workspace", 4),
            Observation {
                turn_id: "turn-1".into(),
                status: OperationStatus::Interrupted,
                attribution: Attribution::Ambiguous,
                tool_call_id: None,
                evidence: "recovery".into(),
            },
        )
        .unwrap();
    let operations = store.operations(sid(), 0, 100).unwrap();
    assert_eq!(operations.items.len(), 2);
    assert_eq!(operations.items[1].before, committed);
    assert_eq!(operations.items[1].status, OperationStatus::Interrupted);
}

#[test]
fn deletion_crash_manifest_is_retryable_and_protects_republished_content() {
    for republish in [false, true] {
        let f = Fixture::new();
        let output = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "store::tests::retention_crash_child",
                "--nocapture",
            ])
            .env("FLOW_B25_CRASH_ROOT", f._temp.path())
            .output()
            .unwrap();
        assert_eq!(
            output.status.code(),
            Some(86),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let store = f.open();
        assert_eq!(store.storage_report().unwrap().pending_deletions, 1);
        let blob = ContentId::hash(b"only selected session");
        let path = store
            .directory()
            .join("blobs")
            .join(format!("{}.zst", blob.as_str()));
        assert!(path.exists());
        if republish {
            store
                .publish_snapshot(key("reuse"), capture(b"only selected session", 3))
                .unwrap();
        }
        store.finish_retention().unwrap();
        assert_eq!(path.exists(), republish);
        assert_eq!(store.storage_report().unwrap().pending_deletions, 0);
        drop(store);
        assert!(f.open().storage_report().unwrap().writable);
    }
}
#[test]
fn retention_crash_child() {
    let Some(root) = std::env::var_os("FLOW_B25_CRASH_ROOT") else {
        return;
    };
    let root = PathBuf::from(root);
    let project = root.join("repository with spaces");
    let store = HistoryStore::open(&project, &root.join("data")).unwrap();
    store
        .register_workspace(key("workspace"), workspace(&project))
        .unwrap();
    retention_fixture(&store);
    store
        .call(|db| {
            db.crash = Some(Boundary::AfterCommit);
            Ok(())
        })
        .unwrap();
    store.delete_closed_session(sid(), ws()).unwrap();
    panic!("expected crash");
}

#[test]
fn sqlite_disk_full_and_lock_fail_without_phantom_operation() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("base"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(&store, baseline.clone());
    // SQLite's actual SQLITE_FULL path, without filling the user's filesystem.
    store
        .call(|db| {
            let pages: i64 = db
                .conn
                .pragma_query_value(None, "page_count", |r| r.get(0))?;
            db.conn.pragma_update(None, "max_page_count", pages)?;
            Ok(())
        })
        .unwrap();
    assert!(
        store
            .agent_event(
                sid(),
                None,
                "running".into(),
                "large.event".into(),
                value!({"text":"x".repeat(30000)})
            )
            .is_err()
    );
    assert!(!store.storage_report().unwrap().writable);
    assert_eq!(store.checkpoint(sid()).unwrap().id, baseline);
    assert!(store.operations(sid(), 0, 100).unwrap().items.is_empty());
    drop(store);
    let store = f.open();
    let lock = Connection::open(store.directory().join("history.sqlite3")).unwrap();
    lock.execute_batch("BEGIN IMMEDIATE").unwrap();
    assert!(
        store
            .agent_event(
                sid(),
                None,
                "running".into(),
                "locked.event".into(),
                value!({})
            )
            .is_err()
    );
    lock.execute_batch("ROLLBACK").unwrap();
    assert_eq!(store.checkpoint(sid()).unwrap().id, baseline);
}

#[test]
fn restart_after_operation_commit_uses_durable_checkpoint_once() {
    let f = Fixture::new();
    let output = Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "store::tests::checkpoint_crash_child",
            "--nocapture",
        ])
        .env("FLOW_B25_CHECKPOINT_ROOT", f._temp.path())
        .output()
        .unwrap();
    assert_eq!(
        output.status.code(),
        Some(86),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let store = f.open();
    let mut recorder = Recorder::recover(store.clone(), sid()).unwrap();
    assert_eq!(
        recorder.checkpoint().manifest.files["src/日本語.txt"].blob,
        ContentId::hash(b"durable")
    );
    let observed = recorder
        .observe(
            capture(b"durable", 3),
            Observation {
                turn_id: "turn-1".into(),
                status: OperationStatus::Interrupted,
                attribution: Attribution::Ambiguous,
                tool_call_id: None,
                evidence: "recovery".into(),
            },
        )
        .unwrap();
    assert!(observed.is_none());
    assert_eq!(store.operations(sid(), 0, 100).unwrap().items.len(), 1);
}
#[test]
fn checkpoint_crash_child() {
    let Some(root) = std::env::var_os("FLOW_B25_CHECKPOINT_ROOT") else {
        return;
    };
    let root = PathBuf::from(root);
    let project = root.join("repository with spaces");
    let store = HistoryStore::open(&project, &root.join("data")).unwrap();
    store
        .register_workspace(key("workspace"), workspace(&project))
        .unwrap();
    let baseline = snapshot_id(
        store
            .publish_snapshot(key("base"), capture(b"base", 1))
            .unwrap(),
    );
    make_session(&store, baseline);
    let mut recorder = Recorder::new(store.clone(), sid()).unwrap();
    store
        .call(|db| {
            db.crash = Some(Boundary::OperationCommitted);
            Ok(())
        })
        .unwrap();
    recorder
        .observe(
            capture(b"durable", 2),
            Observation {
                turn_id: "turn-1".into(),
                status: OperationStatus::Completed,
                attribution: Attribution::CheckpointInterval,
                tool_call_id: None,
                evidence: "fixture".into(),
            },
        )
        .unwrap();
    panic!("expected crash before in-memory checkpoint advancement");
}

#[test]
fn retention_preserves_blobs_shared_by_another_workspace_and_rejects_id_reuse() {
    let f = Fixture::new();
    let store = f.open();
    f.register(&store);
    let (_, blob) = retention_fixture(&store);
    let mut original = store.get_session(sid()).unwrap();
    let other_path = f.project.join("other");
    fs::create_dir(&other_path).unwrap();
    let mut other = workspace(&f.project);
    other.id = WorkspaceId::try_from("other-workspace".to_owned()).unwrap();
    other.worktree_path = other_path;
    store
        .register_workspace(key("other-workspace"), other.clone())
        .unwrap();
    let mut input = capture(b"only selected session", 3);
    input.workspace_id = other.id;
    let shared = snapshot_id(store.publish_snapshot(key("shared"), input).unwrap());
    store.delete_closed_session(sid(), ws()).unwrap();
    assert!(store.get_snapshot(shared).is_ok());
    assert_eq!(
        store.read_content(blob, 0, 100).unwrap().bytes,
        b"only selected session"
    );
    original.status = "starting".into();
    assert!(matches!(
        store.create_session(key("recreate"), original),
        Err(Error::Conflict)
    ));
}
