use super::*;
use crate::test_repository as repository;
use repository::{ExpectedFile, Oracle, Repository as Fixture, regular, verify};
use std::{os::unix::fs::symlink, process::Command};

fn manager(repo: &Fixture) -> WorktreeManager {
    WorktreeManager::open(
        &repo.root,
        HistoryStore::open(&repo.root, &repo.data).unwrap(),
        Recovery::RefuseStale,
    )
    .unwrap()
}
fn choice(manager: &WorktreeManager) -> BaselineChoice {
    BaselineChoice::CleanCommit(manager.repository().head.clone())
}

#[test]
fn stable_reads_retry_changed_scans_and_fail_on_continuous_writes() {
    let repo = Fixture::new();
    let mut scans = 0;
    let result = stable_capture(|| {
        scans += 1;
        if scans <= 2 {
            repo.write("tracked.txt", format!("{scans}").as_bytes());
        }
        capture_once(&repo.root, true)
    })
    .unwrap();
    assert_eq!(scans, 3);
    assert_eq!(result["tracked.txt"].bytes, b"2");
    assert!(matches!(
        stable_capture(|| {
            scans += 1;
            repo.write("tracked.txt", format!("{scans}").as_bytes());
            capture_once(&repo.root, true)
        }),
        Err(Error::Unstable)
    ));
}

#[test]
fn clean_commit_preserves_dirty_main_and_exact_baseline() {
    let repo = Fixture::new();
    repo.write(".gitignore", b"ignored*\n");
    repo.write("ignored-but-tracked", b"tracked despite ignore\n");
    repo.write("binary\nfile", &[0, 255, 13, 10]);
    repo.write("run.sh", b"#!/bin/sh\n");
    fs::set_permissions(repo.root.join("run.sh"), fs::Permissions::from_mode(0o755)).unwrap();
    symlink("/not/a/real/target", repo.root.join("link")).unwrap();
    repo.git(&["add", "--", ".gitignore", "binary\nfile", "run.sh", "link"]);
    repo.git(&["add", "-f", "--", "ignored-but-tracked"]);
    repo.git(&["commit", "-m", "baseline file types"]);
    repo.write("tracked.txt", b"staged\n");
    repo.git(&["add", "tracked.txt"]);
    repo.write("tracked.txt", b"unsaved-on-disk-overlay\n");
    repo.write("untracked.txt", b"developer untracked\n");
    repo.write("ignored-local", b"developer ignored\n");
    let before = source_fingerprint(&repo.root).unwrap();
    let index = fs::read(repo.root.join(".git/index")).unwrap();
    let manager = manager(&repo);
    let prepared = manager.prepare(choice(&manager)).unwrap();
    let record = &prepared.preparation;
    let expected = Oracle::from([
        ("tracked.txt".into(), regular(b"base\n")),
        (".gitignore".into(), regular(b"ignored*\n")),
        (
            "ignored-but-tracked".into(),
            regular(b"tracked despite ignore\n"),
        ),
        ("binary\nfile".into(), regular(&[0, 255, 13, 10])),
        (
            "run.sh".into(),
            ExpectedFile(b"#!/bin/sh\n".to_vec(), 0o755, FileKind::Regular),
        ),
        (
            "link".into(),
            ExpectedFile(
                b"/not/a/real/target".to_vec(),
                fs::symlink_metadata(record.workspace.worktree_path.join("link"))
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                FileKind::Symlink,
            ),
        ),
    ]);
    // Verify historical content with the B05 independently authored oracle.
    let baseline = record.baseline.clone().unwrap();
    let snapshot = manager.store.get_snapshot(baseline.clone()).unwrap();
    assert!(snapshot.manifest.coverage.complete);
    assert!(snapshot.manifest.coverage.excluded.is_empty());
    verify(&manager.store, baseline, &expected).unwrap();
    assert_eq!(source_fingerprint(&repo.root).unwrap(), before);
    assert_eq!(fs::read(repo.root.join(".git/index")).unwrap(), index);
    assert_eq!(
        fs::read(repo.root.join("ignored-local")).unwrap(),
        b"developer ignored\n"
    );
    let id = record.workspace.id.clone();
    let path = record.workspace.worktree_path.clone();
    assert!(matches!(
        manager.prepare(choice(&manager)),
        Err(Error::Busy)
    ));
    drop(prepared);
    fs::write(path.join("tracked.txt"), b"interrupted agent work").unwrap();
    let reacquired = manager.acquire(&id, Recovery::RefuseStale).unwrap();
    assert_eq!(
        fs::read(path.join("tracked.txt")).unwrap(),
        b"interrupted agent work"
    );
    drop(reacquired);
    assert!(
        manager
            .cleanup_incomplete(&id, Recovery::RefuseStale)
            .is_err()
    );
    assert!(path.exists());
}

#[test]
fn fixed_commit_survives_branch_movement_and_changes_during_prepare_fail() {
    let repo = Fixture::new();
    let manager = manager(&repo);
    let selected = choice(&manager);
    repo.write("tracked.txt", b"new commit");
    repo.git(&["commit", "-am", "branch advanced"]);
    let prepared = manager.prepare(selected).unwrap();
    assert_eq!(
        fs::read(
            prepared
                .preparation
                .workspace
                .worktree_path
                .join("tracked.txt")
        )
        .unwrap(),
        b"base\n"
    );
    drop(prepared);
    let result = manager.prepare_inner(choice(&manager), Recovery::RefuseStale, |stage| {
        if stage == Stage::Created {
            repo.write("tracked.txt", b"concurrent developer change");
        }
        Ok(())
    });
    assert!(matches!(
        result,
        Err(Error::Invalid(
            "main project changed during preparation; retry after inspecting the retained worktree"
        ))
    ));
    assert_eq!(
        fs::read(repo.root.join("tracked.txt")).unwrap(),
        b"concurrent developer change"
    );
    assert_eq!(manager.preparations().unwrap().len(), 2);
}

#[test]
fn repository_lock_spans_data_roots_linked_projects_and_guard_lifetime() {
    let repo = Fixture::new();
    let manager = manager(&repo);
    let other_data = repo.temp.path().join("other-data");
    fs::create_dir(&other_data).unwrap();
    let other = HistoryStore::open(&repo.root, &other_data).unwrap();
    assert!(matches!(
        WorktreeManager::open(&repo.root, other.clone(), Recovery::RefuseStale),
        Err(Error::Busy)
    ));
    let prepared = manager.prepare(choice(&manager)).unwrap();
    let path = prepared.preparation.workspace.worktree_path.clone();
    let linked_store = HistoryStore::open(&path, &other_data).unwrap();
    assert!(matches!(
        WorktreeManager::open(&path, linked_store, Recovery::RefuseStale),
        Err(Error::Busy)
    ));
    drop(manager);
    assert!(matches!(
        WorktreeManager::open(&repo.root, other.clone(), Recovery::RefuseStale),
        Err(Error::Busy)
    ));
    drop(prepared);
    assert!(WorktreeManager::open(&repo.root, other, Recovery::RefuseStale).is_ok());
}

#[test]
fn failed_preparations_are_retained_and_cleanup_never_forces() {
    let repo = Fixture::new();
    let manager = manager(&repo);
    for failure in [Stage::Reserved, Stage::Created] {
        let result = manager.prepare_inner(choice(&manager), Recovery::RefuseStale, |stage| {
            if stage == failure {
                Err(Error::Invalid("injected interruption"))
            } else {
                Ok(())
            }
        });
        assert!(result.is_err());
        let record = manager
            .preparations()
            .unwrap()
            .into_iter()
            .find(|r| r.stage == failure)
            .unwrap();
        if failure == Stage::Created {
            fs::write(
                record.workspace.worktree_path.join("unexpected"),
                b"keep me",
            )
            .unwrap();
            assert!(
                manager
                    .cleanup_incomplete(&record.workspace.id, Recovery::RefuseStale)
                    .is_err()
            );
            assert_eq!(
                fs::read(record.workspace.worktree_path.join("unexpected")).unwrap(),
                b"keep me"
            );
            fs::remove_file(record.workspace.worktree_path.join("unexpected")).unwrap();
        }
        manager
            .cleanup_incomplete(&record.workspace.id, Recovery::RefuseStale)
            .unwrap();
        assert!(!record.workspace.worktree_path.exists());
        assert_eq!(
            manager.load(&record.workspace.id).unwrap().stage,
            Stage::Removed
        );
    }
}

#[test]
fn changed_ownership_or_symlink_cannot_remove_other_files() {
    let repo = Fixture::new();
    let manager = manager(&repo);
    assert!(
        manager
            .prepare_inner(choice(&manager), Recovery::RefuseStale, |stage| {
                if stage == Stage::Created {
                    Err(Error::Invalid("injected interruption"))
                } else {
                    Ok(())
                }
            })
            .is_err()
    );
    let record = manager.preparations().unwrap().pop().unwrap();
    let tree = &record.workspace.worktree_path;
    let saved = tree.with_file_name("retained-tree");
    fs::rename(tree, &saved).unwrap();
    symlink(&repo.root, tree).unwrap();
    assert!(
        manager
            .cleanup_incomplete(&record.workspace.id, Recovery::RefuseStale)
            .is_err()
    );
    assert_eq!(fs::read(repo.root.join("tracked.txt")).unwrap(), b"base\n");
    fs::remove_file(tree).unwrap();
    fs::rename(saved, tree).unwrap();
    let admin =
        PathBuf::from(text(git(tree, ["rev-parse", "--absolute-git-dir"]).unwrap()).unwrap());
    fs::write(admin.join("locked"), b"someone else's worktree\n").unwrap();
    assert!(
        manager
            .cleanup_incomplete(&record.workspace.id, Recovery::RefuseStale)
            .is_err()
    );
    assert!(tree.exists());
}

#[test]
fn unsupported_repositories_and_filters_fail_without_checkout() {
    for mode in [
        "sparse",
        "unmerged",
        "gitlink",
        "filter",
        "attributes",
        "assume",
    ] {
        let repo = Fixture::new();
        match mode {
            "sparse" => {
                repo.git(&["sparse-checkout", "init", "--cone"]);
            }
            "unmerged" => {
                repo.git(&["checkout", "-b", "other"]);
                repo.write("tracked.txt", b"other");
                repo.git(&["commit", "-am", "other"]);
                repo.git(&["checkout", "main"]);
                repo.write("tracked.txt", b"main");
                repo.git(&["commit", "-am", "main"]);
                assert!(!repo.git_output(&["merge", "other"]).status.success());
            }
            "gitlink" => {
                let commit = text(repo.git(&["rev-parse", "HEAD"])).unwrap();
                repo.git(&[
                    "update-index",
                    "--add",
                    "--cacheinfo",
                    &format!("160000,{commit},module"),
                ]);
            }
            "filter" => {
                repo.git(&["config", "filter.danger.smudge", "touch SHOULD_NOT_EXIST"]);
            }
            "attributes" => {
                repo.write(".gitattributes", b"* filter=lfs\n");
                repo.git(&["add", ".gitattributes"]);
            }
            "assume" => {
                repo.git(&["update-index", "--assume-unchanged", "tracked.txt"]);
            }
            _ => unreachable!(),
        }
        let head = repo.git(&["rev-parse", "HEAD"]);
        let index = fs::read(repo.root.join(".git/index")).unwrap();
        assert!(Repository::discover(&repo.root).is_err(), "{mode}");
        assert!(!repo.root.join("SHOULD_NOT_EXIST").exists());
        assert_eq!(repo.git(&["rev-parse", "HEAD"]), head);
        assert_eq!(fs::read(repo.root.join(".git/index")).unwrap(), index);
    }
}

#[test]
fn discovery_keeps_tracked_ignored_files_and_does_not_follow_symlinks() {
    let repo = Fixture::new();
    repo.write(".gitignore", b"tracked.txt\nignored\n");
    repo.write("ignored", b"excluded");
    repo.write(".hidden", b"included");
    let outside = repo.temp.path().join("outside");
    fs::write(&outside, b"must not read").unwrap();
    symlink(&outside, repo.root.join("link")).unwrap();
    let files = capture(&repo.root).unwrap();
    assert!(files.contains_key("tracked.txt"));
    assert!(files.contains_key(".hidden"));
    assert!(!files.contains_key("ignored"));
    assert!(!files.keys().any(|p| p.starts_with(".git/")));
    assert_eq!(files["link"].bytes, outside.as_os_str().as_bytes());
    repo.write("dir/file", b"tracked nested");
    repo.git(&["add", "dir/file"]);
    fs::remove_dir_all(repo.root.join("dir")).unwrap();
    assert!(!capture(&repo.root).unwrap().contains_key("dir/file"));
    symlink(repo.temp.path(), repo.root.join("dir")).unwrap();
    assert!(capture(&repo.root).is_err());
}

#[test]
fn historical_filter_commit_is_rejected_before_checkout_and_paths_cannot_escape() {
    let repo = Fixture::new();
    repo.write(".gitattributes", b"tracked.txt filter=lfs\n");
    repo.git(&["add", ".gitattributes"]);
    repo.git(&["commit", "-m", "unsupported historical baseline"]);
    let unsupported = text(repo.git(&["rev-parse", "HEAD"])).unwrap();
    repo.git(&["rm", ".gitattributes"]);
    repo.git(&["commit", "-m", "current commit has no filter"]);
    let manager = manager(&repo);
    assert!(
        manager
            .prepare(BaselineChoice::CleanCommit(unsupported))
            .is_err()
    );
    let retained = manager.preparations().unwrap().pop().unwrap();
    assert_eq!(retained.stage, Stage::Reserved);
    assert!(retained.workspace.worktree_path.join(".git").is_file());
    assert!(
        !retained
            .workspace
            .worktree_path
            .join("tracked.txt")
            .exists()
    );
    for id in ["..", ".", "workspace-not-generated"] {
        let id = WorkspaceId::try_from(id.to_owned()).unwrap();
        assert!(matches!(
            manager.load(&id),
            Err(Error::Invalid("invalid owned workspace identity"))
        ));
    }
}

// Real abrupt process exit: releases kernel locks without running Rust Drop.
#[test]
fn crash_helper() {
    let Some(root) = std::env::var_os("FLOW_B07_CRASH_ROOT") else {
        return;
    };
    let data = PathBuf::from(std::env::var_os("FLOW_B07_CRASH_DATA").unwrap());
    let root = PathBuf::from(root);
    let manager = WorktreeManager::open(
        &root,
        HistoryStore::open(&root, &data).unwrap(),
        Recovery::RefuseStale,
    )
    .unwrap();
    let _ = manager.prepare_inner(choice(&manager), Recovery::RefuseStale, |stage| {
        if stage == Stage::Created {
            std::process::exit(23);
        }
        Ok(())
    });
    panic!("crash checkpoint was not reached");
}

#[test]
fn crash_recovery_requires_exact_instance_and_keeps_interrupted_workspace() {
    let repo = Fixture::new();
    let status = Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "worktrees::tests::crash_helper", "--nocapture"])
        .env("FLOW_B07_CRASH_ROOT", &repo.root)
        .env("FLOW_B07_CRASH_DATA", &repo.data)
        .status()
        .unwrap();
    assert_eq!(status.code(), Some(23));
    let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
    let stale = match WorktreeManager::open(&repo.root, store.clone(), Recovery::RefuseStale) {
        Err(Error::StaleOwner(owner)) => owner,
        _ => panic!("expected stale owner"),
    };
    assert_ne!(stale.pid, std::process::id());
    assert!(matches!(
        WorktreeManager::open(
            &repo.root,
            store.clone(),
            Recovery::Acknowledge("wrong".into())
        ),
        Err(Error::StaleOwner(_))
    ));
    let manager =
        WorktreeManager::open(&repo.root, store, Recovery::Acknowledge(stale.instance)).unwrap();
    let record = manager.preparations().unwrap().pop().unwrap();
    assert_eq!(record.stage, Stage::Created);
    assert!(record.workspace.worktree_path.exists());
    let owner: Owner = read(&manager.directory.join("writer.lock")).unwrap();
    assert!(matches!(
        manager.cleanup_incomplete(&record.workspace.id, Recovery::RefuseStale),
        Err(Error::StaleOwner(_))
    ));
    manager
        .cleanup_incomplete(&record.workspace.id, Recovery::Acknowledge(owner.instance))
        .unwrap();
    assert_eq!(
        manager.load(&record.workspace.id).unwrap().stage,
        Stage::Removed
    );
    assert!(manager.prepare(choice(&manager)).is_ok());
}
