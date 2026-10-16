//! Opt-in B27 measurements, owned temporary repositories only. No model calls.
use history::{
    Attribution, Coverage, HistoryStore, Observation, OperationStatus, Recorder, Session,
    SnapshotInput, Turn,
};
use protocol::{MutationKey, ProtocolStore, ReplayParams, SessionId};
use runtime::worktrees::{BaselineChoice, Recovery, WorktreeManager, capture_all};
use serde_json::{Value, json};
use std::{fs, path::Path, process::Command, time::Instant};

fn key(s: &str) -> MutationKey {
    MutationKey::try_from(s.to_owned()).unwrap()
}
fn git(root: &Path, args: &[&str]) -> String {
    let output = Command::new("git")
        .args([
            "-c",
            "core.hooksPath=",
            "-c",
            "core.autocrlf=false",
            "-c",
            "commit.gpgsign=false",
            "-c",
            "user.name=Flow Benchmark",
            "-c",
            "user.email=flow@example.invalid",
        ])
        .args(args)
        .current_dir(root)
        .env_clear()
        .envs(
            ["PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP"]
                .iter()
                .filter_map(|name| std::env::var_os(name).map(|value| (*name, value))),
        )
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env(
            "GIT_CONFIG_GLOBAL",
            if cfg!(windows) { "NUL" } else { "/dev/null" },
        )
        .output()
        .unwrap();
    assert!(output.status.success(), "fixture Git command failed");
    String::from_utf8(output.stdout).unwrap().trim().to_owned()
}
fn stats(mut samples: Vec<f64>) -> Value {
    samples.sort_by(f64::total_cmp);
    json!({"samplesMs":samples,"medianMs":samples[samples.len()/2],"p95Ms":samples[(samples.len()*95).div_ceil(100)-1]})
}
fn measure<T>(f: impl FnOnce() -> T) -> (T, f64) {
    let t = Instant::now();
    let value = f();
    (value, t.elapsed().as_secs_f64() * 1000.)
}
fn filename(i: usize) -> String {
    format!("files/{:04}/{i:06}.txt", i / 100)
}
fn main() {
    let output = std::env::args()
        .nth(1)
        .expect("usage: first_slice_bench OUTPUT.json");
    let application = std::env::args().any(|arg| arg == "--apply");
    let root = tempfile::tempdir().unwrap();
    let mut fixtures = Vec::new();
    for count in if application {
        vec![1000]
    } else {
        vec![1000, 10000, 100000]
    } {
        let repo = root.path().join(format!("repo-{count}"));
        fs::create_dir(&repo).unwrap();
        git(&repo, &["-c", "init.templateDir=", "init", "--quiet"]);
        for i in 0..count {
            let path = repo.join(filename(i));
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, format!("fixture {i:06} {}\n", "x".repeat(239))).unwrap();
        }
        let (first, first_ms) = measure(|| capture_all(&repo));
        if count > history::MAX_ENTRIES {
            assert!(
                first.is_err(),
                "large fixture must retain its documented coverage limit"
            );
            fixtures.push(json!({"files":count,"bytesPerFile":255,"firstScanMs":first_ms,"status":"unsupported","reason":"workspace exceeds history path limit","maxEntries":history::MAX_ENTRIES}));
            continue;
        }
        assert_eq!(first.unwrap().len(), count);
        let scans = (0..20)
            .map(|_| measure(|| capture_all(&repo).unwrap()).1)
            .collect();
        git(&repo, &["add", "."]);
        git(&repo, &["commit", "--quiet", "-m", "Benchmark baseline"]);
        let data = root.path().join(format!("data-{count}"));
        fs::create_dir(&data).unwrap();
        let store = HistoryStore::open(&repo, &data).unwrap();
        let manager = WorktreeManager::open(&repo, store.clone(), Recovery::RefuseStale).unwrap();
        let prepared = manager
            .prepare(BaselineChoice::CleanCommit(
                manager.repository().head.clone(),
            ))
            .unwrap();
        let workspace = &prepared.preparation.workspace;
        let session = SessionId::try_from("benchmark".to_owned()).unwrap();
        store
            .create_session(
                key("session"),
                Session {
                    id: session.clone(),
                    workspace_id: workspace.id.clone(),
                    baseline: prepared.preparation.baseline.clone().unwrap(),
                    agent: "benchmark".into(),
                    adapter_version: "none".into(),
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
                    prompt_provenance: "offline benchmark".into(),
                    upstream_id: None,
                    status: "completed".into(),
                    created_at_ms: 1,
                },
            )
            .unwrap();
        let cursor = store.snapshot().unwrap().high_water;
        let before = store.storage_report().unwrap().bytes;
        let mut recorder = Recorder::new(store.clone(), session.clone()).unwrap();
        let mut captures = Vec::new();
        let capture_rounds = if application { 20_u64 } else { 220 };
        for generation in 0..capture_rounds {
            for i in 0..if generation < 20 { 10 } else { 1 } {
                fs::write(
                    workspace.worktree_path.join(filename(i)),
                    format!(
                        "generation {generation:04} file {i:02} {}\n",
                        "y".repeat(220)
                    ),
                )
                .unwrap();
            }
            let (_, elapsed) = measure(|| {
                recorder
                    .observe(
                        SnapshotInput {
                            workspace_id: workspace.id.clone(),
                            files: capture_all(&workspace.worktree_path).unwrap(),
                            coverage: Coverage {
                                complete: true,
                                excluded: vec![],
                            },
                            started_at_ms: 2 + generation,
                            completed_at_ms: 2 + generation,
                            provenance: "B27 complete stable reconciliation".into(),
                        },
                        Observation {
                            turn_id: "turn".into(),
                            status: OperationStatus::Completed,
                            attribution: Attribution::CheckpointInterval,
                            tool_call_id: None,
                            evidence: "benchmark; not tool-atomic".into(),
                        },
                    )
                    .unwrap()
                    .expect("changed fixture must produce an operation");
            });
            if generation < 20 {
                captures.push(elapsed);
            }
        }
        let access = (0..20)
            .map(|_| measure(|| store.operations(session.clone(), 0, 20).unwrap()).1)
            .collect();
        let operations = store.operations(session.clone(), 0, 20).unwrap();
        assert_eq!(operations.items.len(), 20);
        assert_eq!(operations.next.is_some(), capture_rounds > 20);
        let change = store
            .get_operation(operations.items[0].id.clone())
            .unwrap()
            .changes
            .remove(0);
        let previews = (0..20)
            .map(|_| {
                measure(|| {
                    for entry in [&change.before, &change.after].into_iter().flatten() {
                        store
                            .read_content(entry.blob.clone(), 0, history::MAX_CHUNK_BYTES)
                            .unwrap();
                    }
                })
                .1
            })
            .collect();
        let (replayed, replay_ms) = measure(|| {
            let mut after = cursor;
            let mut events = 0;
            loop {
                let page = store
                    .replay(&ReplayParams {
                        after: after.clone(),
                        limit: 100,
                    })
                    .unwrap();
                assert!(page.events.len() <= 100);
                events += page.events.len();
                if !page.has_more {
                    break;
                }
                after = page.events.last().unwrap().cursor.clone();
            }
            events
        });
        assert_eq!(
            store
                .operations(session.clone(), capture_rounds.saturating_sub(20), 100)
                .unwrap()
                .items
                .len(),
            20
        );
        let growth = store.storage_report().unwrap().bytes - before;
        drop(recorder);
        drop(prepared);
        drop(manager);
        let application_stats = application.then(|| application_bench(&store, &session, &repo));
        drop(store);
        let (_, open_ms) = measure(|| drop(HistoryStore::open(&repo, &data).unwrap()));
        let capture_stats = stats(captures);
        fixtures.push(json!({"files":count,"bytesPerFile":255,"status":"supported","firstScanMs":first_ms,"warmFullScan":stats(scans),"tenFileReconciliationAndDurableCapture":capture_stats,"incremental500msP95Met":capture_stats["p95Ms"].as_f64().unwrap()<500.,"operationFirstPage":stats(access),"smallHistoricalPair":stats(previews),"longHistoryOperations":capture_rounds,"storageGrowthBytes":growth,"replayEvents":replayed,"replayMs":replay_ms,"reopenWithIntegrityAuditMs":open_ms,"application":application_stats}));
    }
    let report = json!({"schemaVersion":1,"mode":"offline-owned-fixtures","applicationEnabled":application,"machine":std::env::var("FLOW_BENCH_MACHINE").unwrap_or_else(|_|"unlabelled reference machine".into()),"os":std::env::consts::OS,"architecture":std::env::consts::ARCH,"logicalCpus":std::thread::available_parallelism().unwrap().get(),"cacheState":"first capture follows fixture creation; warm runs reuse OS caches; caches were not flushed","exclusions":[".git control data only"],"fixtures":fixtures,"notMeasured":["full IDE process startup","visible UI responsiveness","real adapter latency","100k-file complete capture (unsupported)"]});
    fs::write(output, serde_json::to_vec_pretty(&report).unwrap()).unwrap();
    println!(
        "B27 offline baseline written; unsupported fixtures were not counted as complete captures."
    );
}

/// Measures planning/authorization/durable receipts and an owned disk writer.
/// The separate native scenario measures IntelliJ VFS/document writes.
fn application_bench(store: &HistoryStore, session: &SessionId, root: &Path) -> Value {
    use protocol::{CandidateDecision, CandidatePrepare, Method};
    let c = runtime::review::prepare(
        store.clone(),
        CandidatePrepare {
            session_id: session.clone(),
            writers_stopped: true,
        },
    )
    .unwrap();
    runtime::review::decide(
        store.clone(),
        CandidateDecision {
            candidate_id: c.id.as_str().into(),
            accept: true,
            writers_stopped: true,
        },
    )
    .unwrap();
    let host = runtime::application::ApplyHost::default();
    let call = |method, params| host.request(store.clone(), method, params).unwrap();
    let mut planning = Vec::new();
    let mut applying = Vec::new();
    let mut rollback = Vec::new();
    for n in 0..5 {
        let (j, time) = measure(|| {
            call(
                Method::ApplyPlan,
                json!({"planId":format!("benchmark-apply-{n}"),"candidateId":c.id,"writersStopped":true}),
            )
        });
        planning.push(time);
        let action = json!({"planId":j["id"],"candidateId":c.id,"writersStopped":true});
        let count = j["files"].as_array().unwrap().len();
        assert_eq!(count, 10);
        for undo in [false, true] {
            let (_, elapsed) = measure(|| {
                call(
                    if undo {
                        Method::ApplyRollback
                    } else {
                        Method::ApplyBegin
                    },
                    action.clone(),
                );
                let indices: Vec<_> = if undo {
                    (0..count).rev().collect()
                } else {
                    (0..count).collect()
                };
                for i in indices {
                    let q = json!({"planId":j["id"],"fileIndex":i,"rollback":undo});
                    let t = call(Method::ApplyAuthorize, q.clone());
                    let path = root.join(t["path"].as_str().unwrap());
                    let bytes = t["replacement"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .map(|b| b.as_u64().unwrap() as u8)
                        .collect::<Vec<_>>();
                    fs::write(&path, bytes).unwrap();
                    fs::File::open(path).unwrap().sync_all().unwrap();
                    call(Method::ApplyReceipt, q);
                }
                call(
                    Method::ApplyFinish,
                    json!({"planId":j["id"],"rollback":undo}),
                );
            });
            if undo {
                rollback.push(elapsed)
            } else {
                applying.push(elapsed)
            }
        }
    }
    json!({"fixture":"B27 1000 files (255 baseline bytes/file) / ten changed files / 20 retained operations","samples":5,"writer":"owned Rust disk fixture with fsync; native IDE tested separately","planning":stats(planning),"applyIncludingJournalAndReconciliation":stats(applying),"conditionalRollback":stats(rollback),"textConflictPolicy":"concurrent same-path changes require native merge; no heuristic auto-merge"})
}
