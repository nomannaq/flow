use super::*;
use crate::review::tests::Fixture;

fn call(host: &ApplyHost, f: &Fixture, method: Method, value: Value) -> Value {
    host.request(f.store.clone(), method, value).unwrap()
}
fn plan(host: &ApplyHost, f: &Fixture) -> Value {
    let c = f.prepare();
    f.decide(&c.id, true).unwrap();
    call(
        host,
        f,
        Method::ApplyPlan,
        json!({"planId":"apply-test","candidateId":c.id,"writersStopped":true}),
    )
}
fn action(j: &Value) -> Value {
    json!({"planId":j["id"],"candidateId":j["candidateId"],"writersStopped":true})
}
fn query(j: &Value, i: usize, rollback: bool) -> Value {
    json!({"planId":j["id"],"fileIndex":i,"rollback":rollback})
}
fn write_ticket(root: &Path, t: &Value) {
    let path = root.join(t["path"].as_str().unwrap());
    if t["replacement"].is_null() {
        fs::remove_file(path).unwrap();
    } else {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let bytes = t["replacement"]
            .as_array()
            .unwrap()
            .iter()
            .map(|n| n.as_u64().unwrap() as u8)
            .collect::<Vec<_>>();
        fs::write(path, bytes).unwrap();
    }
}
#[test]
fn durable_apply_preserves_dirty_index_unrelated_files_and_retries() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    fs::write(f.repo.root.join("staged.txt"), "staged\n").unwrap();
    f.repo.git(&["add", "staged.txt"]);
    fs::write(f.repo.root.join("staged.txt"), "unstaged\n").unwrap();
    fs::write(f.repo.root.join("untracked.txt"), "untracked\n").unwrap();
    let index = fingerprint(&f.repo.root).unwrap();
    let j = plan(&host, &f);
    assert_eq!(j["status"], "planned");
    call(&host, &f, Method::ApplyBegin, action(&j));
    assert!(!f.store.storage_report().unwrap().writable);
    let ticket = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
    assert!(
        host.request(f.store.clone(), Method::ApplyAuthorize, query(&j, 0, false))
            .is_err()
    );
    write_ticket(&f.repo.root, &ticket);
    let receipt = call(&host, &f, Method::ApplyReceipt, query(&j, 0, false));
    assert_eq!(receipt["files"][0]["status"], "applied");
    call(&host, &f, Method::ApplyReceipt, query(&j, 0, false));
    assert_eq!(
        call(&host, &f, Method::ApplyFinish, query(&j, 0, false))["status"],
        "complete"
    );
    assert!(f.store.storage_report().unwrap().writable);
    assert_eq!(fingerprint(&f.repo.root).unwrap(), index);
    assert_eq!(
        fs::read(f.repo.root.join("staged.txt")).unwrap(),
        b"unstaged\n"
    );
    assert_eq!(
        fs::read(f.repo.root.join("untracked.txt")).unwrap(),
        b"untracked\n"
    );
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"candidate result\n"
    );
    // Completion reply loss cannot authorize a second write.
    assert_eq!(
        call(&host, &f, Method::ApplyGet, query(&j, 0, false))["status"],
        "complete"
    );
    assert!(
        host.request(f.store.clone(), Method::ApplyBegin, action(&j))
            .is_err()
    );
}
#[test]
fn stale_target_index_source_and_unaccepted_candidate_block_before_writes() {
    for what in ["target", "index", "source", "review"] {
        let f = Fixture::new(true);
        let host = ApplyHost::default();
        let j = plan(&host, &f);
        match what {
            "target" => fs::write(f.repo.root.join("tracked.txt"), "new developer bytes").unwrap(),
            "index" => {
                fs::write(f.repo.root.join("another.txt"), "staged").unwrap();
                f.repo.git(&["add", "another.txt"]);
            }
            "source" => fs::write(
                f.workspace.worktree_path.join("tracked.txt"),
                "late agent bytes",
            )
            .unwrap(),
            _ => {
                f.decide(
                    &ContentId::try_from(j["candidateId"].as_str().unwrap().to_owned()).unwrap(),
                    false,
                )
                .unwrap();
            }
        }
        assert!(
            host.request(f.store.clone(), Method::ApplyBegin, action(&j))
                .is_err(),
            "{what}"
        );
        assert!(f.store.storage_report().unwrap().writable);
    }
}
#[test]
fn native_resolution_versions_candidate_and_requires_renewed_approval() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    fs::write(f.repo.root.join("tracked.txt"), "developer\n").unwrap();
    let j = plan(&host, &f);
    assert_eq!(j["status"], "conflicted");
    let inputs = call(&host, &f, Method::ApplyPreview, query(&j, 0, false));
    assert_eq!(inputs["baseline"], "base\n");
    assert_eq!(inputs["current"], "developer\n");
    assert_eq!(inputs["candidate"], "candidate result\n");
    let params = json!({"planId":j["id"],"fileIndex":0,"content":"resolved developer + agent\n"});
    let next = call(&host, &f, Method::ApplyResolve, params.clone());
    assert_ne!(next["candidateId"], j["candidateId"]);
    assert_eq!(next["status"], "planned");
    assert_eq!(call(&host, &f, Method::ApplyResolve, params), next);
    assert!(
        host.request(f.store.clone(), Method::ApplyBegin, action(&next))
            .is_err()
    );
    f.decide(
        &ContentId::try_from(next["candidateId"].as_str().unwrap().to_owned()).unwrap(),
        true,
    )
    .unwrap();
    call(&host, &f, Method::ApplyBegin, action(&next));
    let t = call(&host, &f, Method::ApplyAuthorize, query(&next, 0, false));
    write_ticket(&f.repo.root, &t);
    call(&host, &f, Method::ApplyReceipt, query(&next, 0, false));
    call(&host, &f, Method::ApplyFinish, query(&next, 0, false));
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"resolved developer + agent\n"
    );
}
#[test]
fn lost_receipt_and_owner_exit_reconcile_then_conditionally_rollback() {
    for wrote in [false, true] {
        let f = Fixture::new(true);
        let host = ApplyHost::default();
        let j = plan(&host, &f);
        let index = fingerprint(&f.repo.root).unwrap();
        call(&host, &f, Method::ApplyBegin, action(&j));
        let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
        if wrote {
            write_ticket(&f.repo.root, &t);
        }
        drop(host);
        let recovered = ApplyHost::default();
        let r = call(&recovered, &f, Method::ApplyReconcile, action(&j));
        assert_eq!(r["status"], "recoveryRequired");
        assert_eq!(
            r["files"][0]["status"],
            if wrote { "applied" } else { "todo" }
        );
        call(&recovered, &f, Method::ApplyRollback, action(&j));
        if wrote {
            let t = call(&recovered, &f, Method::ApplyAuthorize, query(&j, 0, true));
            write_ticket(&f.repo.root, &t);
            call(&recovered, &f, Method::ApplyReceipt, query(&j, 0, true));
        }
        assert_eq!(
            call(&recovered, &f, Method::ApplyFinish, query(&j, 0, true))["status"],
            "rolledBack"
        );
        assert_eq!(
            fs::read(f.repo.root.join("tracked.txt")).unwrap(),
            b"base\n"
        );
        assert_eq!(fingerprint(&f.repo.root).unwrap(), index);
        assert!(f.store.storage_report().unwrap().writable);
    }
}
#[test]
fn rollback_preserves_intervening_edits_and_never_guesses_success() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    call(&host, &f, Method::ApplyBegin, action(&j));
    let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
    write_ticket(&f.repo.root, &t);
    fs::write(f.repo.root.join("tracked.txt"), "newer developer edit\n").unwrap();
    assert!(
        host.request(f.store.clone(), Method::ApplyReceipt, query(&j, 0, false))
            .is_err()
    );
    let r = call(&host, &f, Method::ApplyRollback, action(&j));
    assert_eq!(r["status"], "recoveryConflict");
    assert!(
        host.request(f.store.clone(), Method::ApplyAuthorize, query(&j, 0, true))
            .is_err()
    );
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"newer developer edit\n"
    );
    assert!(!f.store.storage_report().unwrap().writable);
}
#[test]
fn restart_keeps_preimages_hold_and_unknown_write_without_replay() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    call(&host, &f, Method::ApplyBegin, action(&j));
    let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
    write_ticket(&f.repo.root, &t);
    drop(host);
    let Fixture {
        repo,
        store,
        workspace,
        session,
    } = f;
    drop(store);
    let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
    assert!(!store.storage_report().unwrap().writable);
    let f = Fixture {
        repo,
        store,
        workspace,
        session,
    };
    let host = ApplyHost::default();
    let r = call(&host, &f, Method::ApplyReconcile, action(&j));
    assert_eq!(r["files"][0]["status"], "applied");
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"candidate result\n"
    );
}
#[cfg(unix)]
#[test]
fn traversal_symlinks_case_and_unsupported_paths_are_blocked() {
    for path in [
        "../outside",
        "/absolute",
        ".git/config",
        "dir/.GiT/config",
        "git~1/config",
        ".git~1/config",
        "CON.txt",
        "foo:stream",
        "folder/../x",
        "trailing.",
        "trailing ",
        "日本.txt",
    ] {
        assert!(validate_path(path).is_err(), "{path}");
    }
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let outside = f.repo.data.join("outside");
    fs::write(&outside, "outside sentinel").unwrap();
    fs::remove_file(f.repo.root.join("tracked.txt")).unwrap();
    std::os::unix::fs::symlink(&outside, f.repo.root.join("tracked.txt")).unwrap();
    let c = f.prepare();
    f.decide(&c.id, true).unwrap();
    assert!(
        host.request(
            f.store.clone(),
            Method::ApplyPlan,
            json!({"planId":"unsafe","candidateId":c.id,"writersStopped":true})
        )
        .is_err()
    );
    assert_eq!(fs::read(outside).unwrap(), b"outside sentinel");
    let modes = Fixture::new(true);
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(
        modes.repo.root.join("tracked.txt"),
        fs::Permissions::from_mode(0o755),
    )
    .unwrap();
    let c = modes.prepare();
    modes.decide(&c.id, true).unwrap();
    assert!(
        host.request(
            modes.store.clone(),
            Method::ApplyPlan,
            json!({"planId":"mode-change","candidateId":c.id,"writersStopped":true})
        )
        .is_err()
    );
    assert_eq!(
        fs::metadata(modes.repo.root.join("tracked.txt"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o755
    );
}

#[test]
fn completed_application_can_be_rolled_back_and_conflicts_can_be_manually_reconciled() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    call(&host, &f, Method::ApplyBegin, action(&j));
    let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
    write_ticket(&f.repo.root, &t);
    call(&host, &f, Method::ApplyReceipt, query(&j, 0, false));
    call(&host, &f, Method::ApplyFinish, query(&j, 0, false));
    fs::write(f.repo.root.join("tracked.txt"), "newer edit").unwrap();
    let r = call(&host, &f, Method::ApplyRollback, action(&j));
    assert_eq!(r["status"], "recoveryConflict");
    // Explicit manual restoration is not silently discarded or inferred.
    fs::write(f.repo.root.join("tracked.txt"), "candidate result\n").unwrap();
    let r = call(&host, &f, Method::ApplyRollback, action(&j));
    assert_eq!(r["status"], "rollingBack");
    let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, true));
    write_ticket(&f.repo.root, &t);
    call(&host, &f, Method::ApplyReceipt, query(&j, 0, true));
    call(&host, &f, Method::ApplyFinish, query(&j, 0, true));
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
}
#[test]
fn rename_binary_creation_and_deletion_are_journaled_as_exact_path_effects() {
    use history::{Attribution, Observation, OperationStatus, Recorder};
    let f = Fixture::new(false);
    fs::rename(
        f.workspace.worktree_path.join("tracked.txt"),
        f.workspace.worktree_path.join("renamed.txt"),
    )
    .unwrap();
    fs::write(
        f.workspace.worktree_path.join("binary.bin"),
        [0u8, 255, 13, 10],
    )
    .unwrap();
    let mut recorder = Recorder::new(f.store.clone(), f.session.clone()).unwrap();
    recorder
        .observe(
            SnapshotInput {
                workspace_id: f.workspace.id.clone(),
                files: worktrees::capture_all(&f.workspace.worktree_path).unwrap(),
                coverage: Coverage {
                    complete: true,
                    excluded: vec![],
                },
                started_at_ms: 2,
                completed_at_ms: 2,
                provenance: "fixture rename/binary".into(),
            },
            Observation {
                turn_id: "turn".into(),
                tool_call_id: None,
                status: OperationStatus::Completed,
                attribution: Attribution::CheckpointInterval,
                evidence: "fixture".into(),
            },
        )
        .unwrap();
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    assert_eq!(j["files"].as_array().unwrap().len(), 3);
    call(&host, &f, Method::ApplyBegin, action(&j));
    for i in 0..3 {
        let t = call(&host, &f, Method::ApplyAuthorize, query(&j, i, false));
        write_ticket(&f.repo.root, &t);
        call(&host, &f, Method::ApplyReceipt, query(&j, i, false));
    }
    call(&host, &f, Method::ApplyFinish, query(&j, 0, false));
    assert!(!f.repo.root.join("tracked.txt").exists());
    assert_eq!(
        fs::read(f.repo.root.join("renamed.txt")).unwrap(),
        b"base\n"
    );
    assert_eq!(
        fs::read(f.repo.root.join("binary.bin")).unwrap(),
        [0u8, 255, 13, 10]
    );
    call(&host, &f, Method::ApplyRollback, action(&j));
    for i in (0..3).rev() {
        let t = call(&host, &f, Method::ApplyAuthorize, query(&j, i, true));
        write_ticket(&f.repo.root, &t);
        call(&host, &f, Method::ApplyReceipt, query(&j, i, true));
    }
    call(&host, &f, Method::ApplyFinish, query(&j, 0, true));
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
    assert!(!f.repo.root.join("renamed.txt").exists());
    assert!(!f.repo.root.join("binary.bin").exists());
}
#[test]
fn admission_failure_and_cancellation_do_not_write_or_release_unknown_ownership() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    f.store.set_storage_budget(1).unwrap();
    assert!(
        host.request(f.store.clone(), Method::ApplyBegin, action(&j))
            .is_err()
    );
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
    f.store.set_storage_budget(10 * 1024 * 1024 * 1024).unwrap();
    call(&host, &f, Method::ApplyBegin, action(&j));
    call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
    assert_eq!(
        call(&host, &f, Method::ApplyCancel, query(&j, 0, false))["status"],
        "recoveryRequired"
    );
    assert!(!f.store.storage_report().unwrap().writable);
    // Even a generic hold release cannot make an unresolved journal writable.
    f.store.release_history("apply-apply-test".into()).unwrap();
    assert!(!f.store.storage_report().unwrap().writable);
    call(&host, &f, Method::ApplyRollback, action(&j));
    call(&host, &f, Method::ApplyFinish, query(&j, 0, true));
    assert!(f.store.storage_report().unwrap().writable);
}

#[test]
fn rollback_uses_current_head_and_index_without_restaging_or_resetting_new_staging() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    call(&host, &f, Method::ApplyBegin, action(&j));
    let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
    write_ticket(&f.repo.root, &t);
    call(&host, &f, Method::ApplyReceipt, query(&j, 0, false));
    call(&host, &f, Method::ApplyFinish, query(&j, 0, false));
    fs::write(f.repo.root.join("new-staging.txt"), "new staging").unwrap();
    f.repo.git(&["add", "new-staging.txt"]);
    let newer = fingerprint(&f.repo.root).unwrap();
    call(&host, &f, Method::ApplyRollback, action(&j));
    let t = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, true));
    write_ticket(&f.repo.root, &t);
    call(&host, &f, Method::ApplyReceipt, query(&j, 0, true));
    call(&host, &f, Method::ApplyFinish, query(&j, 0, true));
    assert_eq!(fingerprint(&f.repo.root).unwrap(), newer);
    assert_eq!(
        fs::read(f.repo.root.join("tracked.txt")).unwrap(),
        b"base\n"
    );
}

#[test]
fn b26_seeded_apply_faults_preserve_index_unrelated_bytes_and_recovery_preimages() {
    let corpus: Value = serde_json::from_str(include_str!(
        "../../../../tests/fixtures/adversarial/corpus.json"
    ))
    .unwrap();
    for seed in corpus["seeds"].as_array().unwrap() {
        let seed = seed.as_u64().unwrap();
        let f = Fixture::new(false);
        let bytes = match seed % 4 {
            0 => b"\xef\xbb\xbfBOM\r\n".to_vec(),
            1 => vec![0, 255, 254, seed as u8, 13, 10],
            2 => b"mixed\r\nlines\nand\rbytes".to_vec(),
            _ => format!("candidate seed {seed}\n").into_bytes(),
        };
        fs::write(f.workspace.worktree_path.join("tracked.txt"), &bytes).unwrap();
        fs::write(f.workspace.worktree_path.join("added.bin"), &bytes).unwrap();
        let mut recorder = history::Recorder::new(f.store.clone(), f.session.clone()).unwrap();
        recorder
            .observe(
                SnapshotInput {
                    workspace_id: f.workspace.id.clone(),
                    files: worktrees::capture_all(&f.workspace.worktree_path).unwrap(),
                    coverage: Coverage {
                        complete: true,
                        excluded: vec![],
                    },
                    started_at_ms: 2,
                    completed_at_ms: 2,
                    provenance: format!("B26 seed={seed}"),
                },
                history::Observation {
                    turn_id: "turn".into(),
                    tool_call_id: None,
                    status: history::OperationStatus::Completed,
                    attribution: history::Attribution::CheckpointInterval,
                    evidence: "seeded exact bytes".into(),
                },
            )
            .unwrap();
        drop(recorder);
        fs::write(f.repo.root.join("sentinel.txt"), format!("staged {seed}")).unwrap();
        f.repo.git(&["add", "sentinel.txt"]);
        let sentinel = format!("unstaged {seed}").into_bytes();
        fs::write(f.repo.root.join("sentinel.txt"), &sentinel).unwrap();
        fs::write(f.repo.root.join("untracked.bin"), &bytes).unwrap();
        let index = fingerprint(&f.repo.root).unwrap();
        let host = ApplyHost::default();
        let j = plan(&host, &f);
        let candidate = f
            .store
            .candidate_review(serde_json::from_value(j["candidateId"].clone()).unwrap())
            .unwrap();
        assert_eq!(candidate.id, f.prepare().id, "seed={seed}");
        // Full result identity includes added bytes, not just one displayed file.
        let result = f.store.get_snapshot(candidate.candidate.result).unwrap();
        for path in ["tracked.txt", "added.bin"] {
            assert_eq!(
                f.store
                    .content_bytes(result.manifest.files[path].blob.clone())
                    .unwrap(),
                bytes
            );
        }
        call(&host, &f, Method::ApplyBegin, action(&j));
        let first = call(&host, &f, Method::ApplyAuthorize, query(&j, 0, false));
        let wrote = seed % 4 != 0;
        if wrote {
            write_ticket(&f.repo.root, &first);
        }
        if seed % 4 >= 2 {
            call(&host, &f, Method::ApplyReceipt, query(&j, 0, false));
            let high_water = f.store.snapshot().unwrap().high_water;
            call(&host, &f, Method::ApplyReceipt, query(&j, 0, false));
            assert_eq!(
                f.store.snapshot().unwrap().high_water,
                high_water,
                "duplicate receipt seed={seed}"
            );
        }
        let conflict = seed % 4 == 3;
        let target = f.repo.root.join(first["path"].as_str().unwrap());
        if conflict {
            fs::write(&target, b"newer external writer").unwrap();
        }
        // Lost reply/owner exit: reopen the database, never reexecute the ticket.
        drop(host);
        let Fixture {
            repo,
            store,
            workspace,
            session,
        } = f;
        drop(store);
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let f = Fixture {
            repo,
            store,
            workspace,
            session,
        };
        let recovered = ApplyHost::default();
        let r = call(&recovered, &f, Method::ApplyReconcile, action(&j));
        assert_eq!(
            r["status"],
            if conflict {
                "recoveryConflict"
            } else {
                "recoveryRequired"
            },
            "seed={seed}"
        );
        assert!(
            recovered
                .request(f.store.clone(), Method::ApplyFinish, query(&j, 0, false))
                .is_err(),
            "phantom completion seed={seed}"
        );
        let rollback = call(&recovered, &f, Method::ApplyRollback, action(&j));
        if conflict {
            assert_eq!(rollback["status"], "recoveryConflict");
            assert_eq!(fs::read(target).unwrap(), b"newer external writer");
            assert!(!f.store.storage_report().unwrap().writable);
        } else {
            for (i, file) in rollback["files"]
                .as_array()
                .unwrap()
                .iter()
                .enumerate()
                .rev()
            {
                if file["status"] == "applied" {
                    let t = call(&recovered, &f, Method::ApplyAuthorize, query(&j, i, true));
                    write_ticket(&f.repo.root, &t);
                    call(&recovered, &f, Method::ApplyReceipt, query(&j, i, true));
                }
            }
            assert_eq!(
                call(&recovered, &f, Method::ApplyFinish, query(&j, 0, true))["status"],
                "rolledBack"
            );
            assert_eq!(
                fs::read(f.repo.root.join("tracked.txt")).unwrap(),
                b"base\n"
            );
            assert!(!f.repo.root.join("added.bin").exists());
        }
        assert_eq!(
            fingerprint(&f.repo.root).unwrap(),
            index,
            "index seed={seed}"
        );
        assert_eq!(
            fs::read(f.repo.root.join("sentinel.txt")).unwrap(),
            sentinel
        );
        assert_eq!(fs::read(f.repo.root.join("untracked.bin")).unwrap(), bytes);
        assert_eq!(
            fs::read(f.workspace.worktree_path.join("tracked.txt")).unwrap(),
            bytes
        );
    }
}

#[test]
fn b26_portable_path_corpus_and_seeded_component_mutations_are_rejected() {
    let corpus: Value = serde_json::from_str(include_str!(
        "../../../../tests/fixtures/adversarial/corpus.json"
    ))
    .unwrap();
    for path in corpus["unsafePaths"].as_array().unwrap() {
        let path = path.as_str().unwrap();
        assert!(validate_path(path).is_err(), "path={path:?}");
        for prefix in ["safe/", "safe/nested/"] {
            // An absolute path becomes an empty component when prefixed.
            assert!(
                validate_path(&format!("{prefix}{path}")).is_err(),
                "path={path:?}"
            );
        }
    }
    let mut seed = 26u64;
    for case in 0..2048 {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
        let safe = format!("folder {}/file-{}.txt", seed % 99, case);
        assert!(validate_path(&safe).is_ok());
        let bad = format!("{safe}/../escape-{seed}");
        assert!(validate_path(&bad).is_err(), "seed={seed} case={case}");
    }
}

#[cfg(unix)]
#[test]
fn b26_target_replaced_by_symlink_after_begin_cannot_authorize_a_write() {
    let f = Fixture::new(true);
    let host = ApplyHost::default();
    let j = plan(&host, &f);
    call(&host, &f, Method::ApplyBegin, action(&j));
    let outside = f.repo.data.join("outside sentinel");
    fs::write(&outside, b"outside").unwrap();
    fs::remove_file(f.repo.root.join("tracked.txt")).unwrap();
    std::os::unix::fs::symlink(&outside, f.repo.root.join("tracked.txt")).unwrap();
    assert!(
        host.request(f.store.clone(), Method::ApplyAuthorize, query(&j, 0, false))
            .is_err()
    );
    assert_eq!(fs::read(outside).unwrap(), b"outside");
    assert_ne!(
        call(&host, &f, Method::ApplyGet, query(&j, 0, false))["status"],
        "complete"
    );
}
