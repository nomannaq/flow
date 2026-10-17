use super::*;

#[test]
fn changed_files_update_only_their_fts_rows_and_deleted_facts_disappear() {
    let (_temp, root, store) = fixture();
    fs::write(root.join("a.rs"), "fn alpha() {}\n").unwrap();
    fs::write(root.join("z.rs"), "fn untouched() {}\n").unwrap();
    let token = CancellationToken::new();
    let (_, first) = query_with_stats(
        &store,
        "one",
        &root,
        "repo_overview",
        &ContextQuery::default(),
        &token,
    )
    .unwrap();
    assert_eq!(first.parsed_files, 2);
    let db = store.context_database().unwrap();
    let row: i64 = db
        .query_row("SELECT rowid FROM symbols WHERE path='z.rs'", [], |row| {
            row.get(0)
        })
        .unwrap();
    fs::write(root.join("a.rs"), "fn replacement() {}\nfn extra() {}\n").unwrap();
    let (_, changed) = query_with_stats(
        &store,
        "one",
        &root,
        "repo_overview",
        &ContextQuery::default(),
        &token,
    )
    .unwrap();
    assert_eq!(changed.parsed_files, 1);
    assert_eq!(changed.updated_files, 1);
    assert_eq!(changed.symbol_rows_written, 2);
    assert_eq!(
        row,
        db.query_row("SELECT rowid FROM symbols WHERE path='z.rs'", [], |row| row
            .get::<_, i64>(0))
            .unwrap()
    );
    assert_eq!(
        db.query_row(
            "SELECT count(*) FROM symbols WHERE name='alpha'",
            [],
            |row| row.get::<_, i64>(0)
        )
        .unwrap(),
        0
    );
    fs::rename(root.join("a.rs"), root.join("renamed.rs")).unwrap();
    let (value, renamed) = query_with_stats(
        &store,
        "one",
        &root,
        "search_symbols",
        &ContextQuery {
            query: "replacement".into(),
            ..Default::default()
        },
        &token,
    )
    .unwrap();
    assert_eq!(value["results"][0]["path"], "renamed.rs");
    assert_eq!(renamed.removed_files, 1);
    fs::remove_file(root.join("renamed.rs")).unwrap();
    assert!(
        query(
            &store,
            "one",
            &root,
            "search_symbols",
            &ContextQuery {
                query: "replacement".into(),
                ..Default::default()
            },
            &token
        )
        .unwrap()["results"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[cfg(unix)]
#[test]
fn warm_queries_hash_returned_files_and_detect_same_size_restored_mtime_edits() {
    let (_temp, root, store) = fixture();
    for i in 0..30 {
        fs::write(
            root.join(format!("file{i}.rs")),
            if i == 0 {
                "fn needle() {}\n".to_owned()
            } else {
                format!("fn other{i}() {{}}\n")
            },
        )
        .unwrap();
    }
    let token = CancellationToken::new();
    let request = ContextQuery {
        query: "needle".into(),
        ..Default::default()
    };
    let (first, _) =
        query_with_stats(&store, "one", &root, "search_symbols", &request, &token).unwrap();
    // Cross the same racy-timestamp guard used by the index; no mtime-only cache.
    std::thread::sleep(Duration::from_millis(2100));
    let (_, warm) =
        query_with_stats(&store, "one", &root, "search_symbols", &request, &token).unwrap();
    assert_eq!(warm.parsed_files, 0);
    assert_eq!(warm.reused_files, 30);
    assert_eq!(warm.source_reads, 1);
    assert_eq!(warm.symbol_rows_written, 0);
    let path = root.join("file0.rs");
    let modified = fs::metadata(&path).unwrap().modified().unwrap();
    fs::write(&path, "fn edited() {}\n").unwrap();
    fs::File::options()
        .write(true)
        .open(&path)
        .unwrap()
        .set_times(fs::FileTimes::new().set_modified(modified))
        .unwrap();
    let (next, changed) =
        query_with_stats(&store, "one", &root, "search_symbols", &request, &token).unwrap();
    assert_ne!(next["generation"], first["generation"]);
    assert!(next["results"].as_array().unwrap().is_empty());
    assert_eq!(changed.parsed_files, 1);
}

#[test]
fn project_map_is_local_repeatable_and_refreshes_without_touching_source() {
    let (_temp, root, store) = fixture();
    fs::create_dir(root.join(".git")).unwrap();
    fs::write(root.join(".gitignore"), "private.rs\n").unwrap();
    fs::write(root.join("private.rs"), "fn secret() {}\n").unwrap();
    fs::write(root.join("lib.rs"), "pub fn visible() {}\n").unwrap();
    let cancel = CancellationToken::new();
    let first = initialize(&store, &root, &cancel).unwrap();
    assert_eq!(first["files"], 1);
    assert_eq!(first["symbols"], 1);
    assert_eq!(first["tokenSavings"], "notMeasured");
    let map_path = root.join(".flow/map.md");
    let stamp = fs::metadata(&map_path).unwrap().modified().unwrap();
    let second = initialize(&store, &root, &cancel).unwrap();
    assert_eq!(first["generation"], second["generation"]);
    assert_eq!(first["mapBytes"], second["mapBytes"]);
    assert_eq!(second["indexStats"]["parsedFiles"], 0);
    assert_eq!(stamp, fs::metadata(&map_path).unwrap().modified().unwrap());
    let map = fs::read_to_string(&map_path).unwrap();
    assert!(map.contains("visible"));
    assert!(!map.contains("secret"));
    assert_eq!(
        fs::read_to_string(root.join(".flow/.gitignore")).unwrap(),
        "*\n"
    );
    assert_eq!(
        fs::read_to_string(root.join(".gitignore")).unwrap(),
        "private.rs\n"
    );
    fs::write(root.join("lib.rs"), "pub fn replacement() {}\n").unwrap();
    assert_ne!(
        first["generation"],
        initialize(&store, &root, &cancel).unwrap()["generation"]
    );
    let map = fs::read_to_string(map_path).unwrap();
    assert!(map.contains("replacement"));
    assert!(!map.contains("visible"));
}

#[test]
fn initializer_preserves_unowned_directories_and_cancelled_maps() {
    let (_temp, root, store) = fixture();
    fs::create_dir(root.join(".flow")).unwrap();
    fs::write(root.join(".flow/map.md"), "user data").unwrap();
    assert!(initialize(&store, &root, &CancellationToken::new()).is_err());
    assert_eq!(
        fs::read_to_string(root.join(".flow/map.md")).unwrap(),
        "user data"
    );
    fs::remove_file(root.join(".flow/map.md")).unwrap();
    fs::remove_dir(root.join(".flow")).unwrap();
    initialize(&store, &root, &CancellationToken::new()).unwrap();
    let previous = fs::read(root.join(".flow/map.md")).unwrap();
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(initialize(&store, &root, &cancel).is_err());
    assert_eq!(previous, fs::read(root.join(".flow/map.md")).unwrap());
}

#[cfg(unix)]
#[test]
fn initializer_rejects_redirected_directories_and_files() {
    let (_temp, root, store) = fixture();
    let outside = root.parent().unwrap().join("outside");
    fs::create_dir(&outside).unwrap();
    std::os::unix::fs::symlink(&outside, root.join(".flow")).unwrap();
    assert!(initialize(&store, &root, &CancellationToken::new()).is_err());
    assert_eq!(fs::read_dir(&outside).unwrap().count(), 0);
    fs::remove_file(root.join(".flow")).unwrap();
    initialize(&store, &root, &CancellationToken::new()).unwrap();
    fs::remove_file(root.join(".flow/map.md")).unwrap();
    let target = outside.join("data");
    fs::write(&target, "keep").unwrap();
    std::os::unix::fs::symlink(&target, root.join(".flow/map.md")).unwrap();
    assert!(initialize(&store, &root, &CancellationToken::new()).is_err());
    assert_eq!(fs::read_to_string(target).unwrap(), "keep");
}
fn fixture() -> (tempfile::TempDir, std::path::PathBuf, HistoryStore) {
    let temp = tempfile::tempdir().unwrap();
    let root = temp.path().join("repo");
    let data = temp.path().join("data");
    fs::create_dir(&root).unwrap();
    fs::create_dir(&data).unwrap();
    let store = HistoryStore::open(&root, &data).unwrap();
    (temp, root, store)
}
#[test]
fn isolation_incremental_ranges_fts_and_budgets() {
    let (_temp, root, store) = fixture();
    fs::write(root.join("lib.rs"),"use std::io;\npub struct Engine;\nimpl Engine { pub fn shutdown(&self) {} }\nmacro_rules! call { () => { missing() } }\n").unwrap();
    fs::write(root.join("other.py"), "def secret(): pass").unwrap();
    let token = CancellationToken::new();
    let request = ContextQuery {
        query: "shutdown".into(),
        ..Default::default()
    };
    let result = query(&store, "one", &root, "search_symbols", &request, &token).unwrap();
    assert_eq!(result["results"][0]["symbol"]["name"], "shutdown");
    assert_eq!(result["results"][0]["symbol"]["startLine"], 3);
    assert_eq!(result["results"][0]["symbol"]["confidence"], "syntaxOnly");
    assert!(
        result["results"][0]["symbol"]["parent"]
            .as_str()
            .unwrap()
            .starts_with("Engine")
    );
    let next = query(&store, "one", &root, "search_symbols", &request, &token).unwrap();
    assert_eq!(result, next);
    let other = root.parent().unwrap().join("worktree");
    fs::create_dir(&other).unwrap();
    fs::write(other.join("lib.rs"), "fn different() {}").unwrap();
    assert!(
        query(&store, "two", &other, "search_symbols", &request, &token).unwrap()["results"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    fs::write(root.join("lib.rs"), "fn changed() {}\nfn incomplete(").unwrap();
    let fresh = query(
        &store,
        "one",
        &root,
        "check_context_freshness",
        &ContextQuery {
            path: Some("lib.rs".into()),
            content_hash: Some(result["results"][0]["contentHash"].as_str().unwrap().into()),
            ..Default::default()
        },
        &token,
    )
    .unwrap();
    assert_eq!(fresh["freshness"], "stale");
    assert_ne!(fresh["generation"], result["generation"]);
    fs::remove_file(root.join("lib.rs")).unwrap();
    assert!(
        query(
            &store,
            "one",
            &root,
            "repo_overview",
            &ContextQuery::default(),
            &token
        )
        .unwrap()["results"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    for i in 0..100 {
        fs::write(
            root.join(format!("file{i}.rs")),
            format!("fn symbol{i}() {{}}\n"),
        )
        .unwrap();
    }
    let result = query(
        &store,
        "one",
        &root,
        "repo_overview",
        &ContextQuery {
            byte_budget: Some(1024),
            ..Default::default()
        },
        &token,
    )
    .unwrap();
    assert!(result.to_string().len() <= 1024);
    assert_eq!(result["truncated"], true);
}
#[test]
fn cancelled_refresh_preserves_generation_and_parser_upgrade_rebuilds() {
    let (_temp, root, store) = fixture();
    fs::write(root.join("lib.rs"), "fn original() {}").unwrap();
    let token = CancellationToken::new();
    let initial = query(
        &store,
        "one",
        &root,
        "repo_overview",
        &ContextQuery::default(),
        &token,
    )
    .unwrap();
    fs::write(root.join("lib.rs"), "fn next() {}").unwrap();
    token.cancel();
    assert!(
        query(
            &store,
            "one",
            &root,
            "repo_overview",
            &ContextQuery::default(),
            &token
        )
        .is_err()
    );
    let db = store.context_database().unwrap();
    let generation: String = db
        .query_row("SELECT generation FROM generations", [], |r| r.get(0))
        .unwrap();
    assert_eq!(generation, initial["generation"]);
    db.execute("UPDATE generations SET parser='old-grammar'", [])
        .unwrap();
    let value = query(
        &store,
        "one",
        &root,
        "search_symbols",
        &ContextQuery {
            query: "next".into(),
            ..Default::default()
        },
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(value["results"][0]["symbol"]["name"], "next");
    for path in ["../secret.rs", "/tmp/secret.rs", "a/../../secret.rs"] {
        assert!(
            query(
                &store,
                "one",
                &root,
                "get_symbol_context",
                &ContextQuery {
                    path: Some(path.into()),
                    ..Default::default()
                },
                &CancellationToken::new()
            )
            .is_err()
        );
    }
}
#[cfg(unix)]
#[test]
fn symlinks_and_ignored_files_never_enter_context() {
    let (_temp, root, store) = fixture();
    fs::create_dir(root.join(".git")).unwrap();
    fs::write(root.join(".gitignore"), "private.rs\n").unwrap();
    fs::write(root.join("private.rs"), "fn hidden() {}").unwrap();
    std::os::unix::fs::symlink(root.join("private.rs"), root.join("linked.rs")).unwrap();
    let value = query(
        &store,
        "one",
        &root,
        "repo_overview",
        &ContextQuery::default(),
        &CancellationToken::new(),
    )
    .unwrap();
    assert!(value["results"].as_array().unwrap().is_empty());
}
#[test]
fn retrieval_corpus_and_index_query_baseline() {
    let (_temp, root, store) = fixture();
    for i in 0..200 {
        fs::write(
            root.join(format!("file{i}.rs")),
            format!("pub fn read_file_{i}() {{}}\npub fn shutdown_engine_{i}() {{}}\n"),
        )
        .unwrap();
    }
    let token = CancellationToken::new();
    let started = Instant::now();
    query(
        &store,
        "one",
        &root,
        "repo_overview",
        &ContextQuery::default(),
        &token,
    )
    .unwrap();
    let indexing = started.elapsed();
    let started = Instant::now();
    for term in ["shutdown_engine_42", "read_file_123"] {
        let result = query(
            &store,
            "one",
            &root,
            "search_symbols",
            &ContextQuery {
                query: term.into(),
                ..Default::default()
            },
            &token,
        )
        .unwrap();
        assert_eq!(result["results"][0]["symbol"]["name"], term);
    }
    let queries = started.elapsed();
    let started = Instant::now();
    let basic = fs::read_dir(&root)
        .unwrap()
        .filter_map(|p| fs::read_to_string(p.ok()?.path()).ok())
        .filter(|s| s.contains("shutdown_engine_42"))
        .count();
    assert_eq!(basic, 1);
    eprintln!(
        "context corpus: 200 files, 400 symbols, exact rank@1=2/2; index={indexing:?}, two verified queries={queries:?}, basic text scan={:?}",
        started.elapsed()
    );
}

#[test]
fn nested_context_paths_use_portable_slashes() {
    let (_temp, root, store) = fixture();
    fs::create_dir(root.join("src")).unwrap();
    fs::write(root.join("src/lib.rs"), "pub fn nested() {}\n").unwrap();
    let value = query(
        &store,
        "one",
        &root,
        "get_symbol_context",
        &ContextQuery {
            path: Some("src/lib.rs".into()),
            line: Some(1),
            ..Default::default()
        },
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(value["results"][0]["path"], "src/lib.rs");
    assert_eq!(value["results"][0]["symbol"]["name"], "nested");
}
