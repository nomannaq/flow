use super::*;

#[test]
fn every_bundled_language_extracts_real_declarations_and_calls() {
    let cases = [
        ("a.rs", "fn helper() {} fn main() { helper(); }"),
        (
            "a.ts",
            "export function helper(): void {} export function main() { helper(); }",
        ),
        (
            "a.tsx",
            "function helper() {} function main() { helper(); return <div/>; }",
        ),
        (
            "a.vue",
            "<template>🦀 helper()</template>\n<script setup lang=\"ts\">\nfunction helper() {}\nfunction main() { helper(); }\n</script>",
        ),
        ("a.js", "function helper() {} function main() { helper(); }"),
        (
            "a.py",
            "def helper():\n    pass\ndef main():\n    helper()\n",
        ),
        (
            "a.go",
            "package main\nfunc helper() {}\nfunc main() { helper() }\n",
        ),
        (
            "A.java",
            "class A { static void helper() {} void main() { helper(); } }",
        ),
        ("a.kt", "fun helper() {}\nfun main() { helper() }"),
        ("a.swift", "func helper() {}\nfunc main() { helper() }"),
        (
            "a.php",
            "<?php function helper() {} function main() { helper(); }",
        ),
        (
            "a.r",
            "helper <- function() {}\nmain <- function() { helper() }",
        ),
        (
            "a.c",
            "void helper(void) {} int main(void) { helper(); return 0; }",
        ),
        (
            "a.cpp",
            "void helper() {} int main() { helper(); return 0; }",
        ),
        ("a.rb", "def helper\nend\ndef main\n  helper()\nend"),
        (
            "a.cs",
            "class A { static void helper() {} void main() { helper(); } }",
        ),
        (
            "a.scala",
            "object A { def helper(): Unit = (); def main(): Unit = { helper() } }",
        ),
        (
            "a.ex",
            "defmodule A do\n  def helper(), do: :ok\n  def main(), do: helper()\nend",
        ),
        (
            "a.sol",
            "contract A { function helper() public {} function main() public { helper(); } }",
        ),
        ("a.ml", "let helper () = ()\nlet main () = helper ()\n"),
        (
            "a.zig",
            "fn helper() void {}\npub fn main() void { helper(); }",
        ),
        ("a.dart", "void helper() {} void main() { helper(); }"),
        ("a.clj", "(defn helper [] nil)\n(defn main [] (helper))"),
        ("a.nix", "let helper = x: x; main = x: helper x; in main"),
        (
            "a.lua",
            "function helper() end\nfunction main() helper() end",
        ),
        (
            "a.gleam",
            "pub fn helper() { Nil }\npub fn main() { helper() }",
        ),
    ];
    let cancel = CancellationToken::new();
    let mut failures = Vec::new();
    for (path, source) in cases {
        let (symbols, references, _) =
            syntax::extract(path, source, &cancel, Instant::now()).unwrap();
        if !symbols.iter().any(|s| s.name == "helper")
            || !references
                .iter()
                .any(|r| r.name == "helper" && r.relation == "calls")
        {
            let mut parser = tree_sitter::Parser::new();
            parser
                .set_language(&syntax::grammar(path).unwrap().language)
                .unwrap();
            failures.push(format!(
                "{path}: names={:?}, calls={:?}, tree={}",
                symbols.iter().map(|s| &s.name).collect::<Vec<_>>(),
                references,
                parser.parse(source, None).unwrap().root_node().to_sexp()
            ));
        }
        for symbol in symbols {
            assert!(
                source.get(symbol.start_byte..symbol.end_byte).is_some(),
                "{path}"
            );
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

fn run(store: &HistoryStore, root: &Path, method: &str, request: ContextQuery) -> Value {
    query(
        store,
        "one",
        root,
        method,
        &request,
        &CancellationToken::new(),
    )
    .unwrap()
}

#[test]
fn imports_receivers_overloads_and_ambiguous_calls_are_conservative() {
    let (_temp, root, store) = tests::fixture();
    fs::write(root.join("utils.ts"), "export function work() {}\n").unwrap();
    fs::write(root.join("main.ts"),"import { work as renamed } from './utils';\nexport function start() { renamed(); }\nclass Engine { stop() {} }\nclass Other { stop() {} }\nfunction close(engine: Engine) { engine.stop(); }\n").unwrap();
    let graph = run(&store, &root, "get_graph", ContextQuery::default());
    let rows = graph["results"].as_array().unwrap();
    let node_name = |id: &Value| {
        rows.iter()
            .find(|n| n["id"] == *id)
            .map(|n| n["name"].as_str().unwrap())
            .unwrap_or("?")
    };
    assert!(
        rows.iter().any(|r| r["edge"]["relation"] == "calls"
            && node_name(&r["edge"]["source"]) == "start"
            && node_name(&r["edge"]["target"]) == "work"),
        "{graph}"
    );
    assert!(
        rows.iter()
            .any(|r| r["edge"]["confidence"] == "receiverBound"
                && node_name(&r["edge"]["source"]) == "close"
                && node_name(&r["edge"]["target"]) == "stop"),
        "{graph}"
    );
    fs::write(
        root.join("A.java"),
        "class A { void work(int n) {} void work(int a, int b) {} void main() { work(1); } }",
    )
    .unwrap();
    fs::write(root.join("other.ts"), "export function collision() {}\n").unwrap();
    fs::write(root.join("third.ts"), "export function collision() {}\n").unwrap();
    fs::write(
        root.join("unknown.ts"),
        "function caller() { collision(); }\n",
    )
    .unwrap();
    let trace = run(
        &store,
        &root,
        "trace_calls",
        ContextQuery {
            symbol: Some("A.main".into()),
            direction: Some("out".into()),
            ..Default::default()
        },
    );
    assert_eq!(
        trace["results"][0]["target"]["signature"], "void work(int n) ",
        "{trace}"
    );
    let trace = run(
        &store,
        &root,
        "trace_calls",
        ContextQuery {
            symbol: Some("caller".into()),
            direction: Some("out".into()),
            ..Default::default()
        },
    );
    assert!(trace["results"].as_array().unwrap().is_empty(), "{trace}");
    assert!(trace["unresolvedReferences"].as_u64().unwrap() > 0);
}

#[test]
fn cycles_file_impact_scopes_regex_and_cache_recovery() {
    let (_temp, root, store) = tests::fixture();
    fs::create_dir(root.join("src")).unwrap();
    fs::create_dir(root.join("src-other")).unwrap();
    fs::write(
        root.join("src/a.py"),
        "def alpha():\n    beta()\ndef beta():\n    alpha()\n",
    )
    .unwrap();
    fs::write(
        root.join("src/b.py"),
        "from a import alpha\ndef caller():\n    alpha()\n",
    )
    .unwrap();
    fs::write(root.join("src-other/c.py"), "def outsider(): pass\n").unwrap();
    let trace = run(
        &store,
        &root,
        "trace_calls",
        ContextQuery {
            symbol: Some("src/a.py".into()),
            depth: Some(32),
            ..Default::default()
        },
    );
    assert!(
        trace["results"]
            .as_array()
            .unwrap()
            .iter()
            .any(|r| r["source"]["name"] == "caller"),
        "{trace}"
    );
    assert!(trace["results"].as_array().unwrap().len() < 10);
    let search = run(
        &store,
        &root,
        "find_all",
        ContextQuery {
            pattern: Some("alpha|beta".into()),
            scope: Some("src".into()),
            limit: Some(2),
            ..Default::default()
        },
    );
    assert_eq!(search["results"].as_array().unwrap().len(), 2);
    assert_eq!(search["truncated"], true);
    assert!(
        search["results"]
            .as_array()
            .unwrap()
            .iter()
            .all(|r| r["path"].as_str().unwrap().starts_with("src/"))
    );
    let check = run(&store, &root, "check_index", ContextQuery::default());
    assert_eq!(check["freshness"], "verifiedAtRead");
    fs::write(root.join("src/a.py"), "def replacement(): pass\n").unwrap();
    let check = run(&store, &root, "check_index", ContextQuery::default());
    assert_eq!(check["freshness"], "stale");
    assert_eq!(check["repaired"], false);
    assert_eq!(
        run(&store, &root, "check_index", ContextQuery::default())["freshness"],
        "stale"
    );
    let found = run(
        &store,
        &root,
        "find_code",
        ContextQuery {
            query: "replacement".into(),
            ..Default::default()
        },
    );
    assert_eq!(found["results"][0]["name"], "replacement");
    store
        .context_database()
        .unwrap()
        .execute("UPDATE graphs SET data='{}'", [])
        .unwrap();
    assert_eq!(
        run(
            &store,
            &root,
            "find_code",
            ContextQuery {
                query: "replacement".into(),
                ..Default::default()
            }
        )["results"][0]["name"],
        "replacement"
    );
    for method in graph::METHODS {
        let request = ContextQuery {
            query: "replacement".into(),
            path: Some("src/a.py".into()),
            symbol: Some("replacement".into()),
            byte_budget: Some(1024),
            fixed: true,
            ..Default::default()
        };
        let value = run(&store, &root, method, request);
        assert!(value.to_string().len() <= 1024, "{method}: {value}");
    }
}

#[test]
fn reviewed_learnings_are_immutable_relevant_and_private_when_requested() {
    let (_temp, root, store) = tests::fixture();
    fs::create_dir(root.join("src")).unwrap();
    fs::write(root.join("src/pay.rs"), "pub fn invoice_amount() {}\n").unwrap();
    let cancel = CancellationToken::new();
    let mut note = LearningWrite {
        title: "Amounts use cents".into(),
        body: "Keep money as integer cents.".into(),
        author: "Reviewer".into(),
        touches: vec!["src".into()],
        constraints: vec!["No float currency".into()],
        ..Default::default()
    };
    let first = save_learning(&store, &root, &note, &cancel).unwrap();
    assert_eq!(first, save_learning(&store, &root, &note, &cancel).unwrap());
    let original = fs::read(root.join(first["path"].as_str().unwrap())).unwrap();
    note.body = "Use checked integer cents.".into();
    note.replaces = vec![first["id"].as_str().unwrap().into()];
    let second = save_learning(&store, &root, &note, &cancel).unwrap();
    assert_ne!(first["id"], second["id"]);
    assert_eq!(
        original,
        fs::read(root.join(first["path"].as_str().unwrap())).unwrap()
    );
    let notes = run(&store, &root, "list_learnings", ContextQuery::default());
    assert_eq!(notes["results"].as_array().unwrap().len(), 1);
    assert_eq!(notes["results"][0]["body"], note.body);
    let review = run(
        &store,
        &root,
        "review_learnings",
        ContextQuery {
            paths: vec!["src/pay.rs".into()],
            ..Default::default()
        },
    );
    assert_eq!(review["results"][0]["review"], "humanReviewRequired");
    let found = run(
        &store,
        &root,
        "find_code",
        ContextQuery {
            query: "invoice amount".into(),
            ..Default::default()
        },
    );
    assert_eq!(found["learnings"]["results"][0]["title"], note.title);
    note.private = true;
    note.replaces.clear();
    let private = save_learning(&store, &root, &note, &cancel).unwrap();
    assert_eq!(private["private"], true);
    assert!(!root.join(private["path"].as_str().unwrap()).exists());
    note.touches = vec!["../outside".into()];
    assert!(save_learning(&store, &root, &note, &cancel).is_err());
    let foreign = root.parent().unwrap().join("foreign");
    fs::create_dir(&foreign).unwrap();
    assert_eq!(
        run(&store, &foreign, "list_learnings", ContextQuery::default())["results"]
            .as_array()
            .unwrap()
            .len(),
        1,
        "only the personal note follows across projects"
    );
}

#[cfg(unix)]
#[test]
fn learning_directories_cannot_redirect_writes() {
    let (_temp, root, store) = tests::fixture();
    let outside = root.parent().unwrap().join("elsewhere");
    fs::create_dir(&outside).unwrap();
    std::os::unix::fs::symlink(&outside, root.join(".flow-notes")).unwrap();
    assert!(
        save_learning(
            &store,
            &root,
            &LearningWrite {
                title: "Decision".into(),
                author: "Me".into(),
                body: "Reviewed".into(),
                ..Default::default()
            },
            &CancellationToken::new()
        )
        .is_err()
    );
    assert_eq!(fs::read_dir(outside).unwrap().count(), 0);
}

#[test]
fn scoped_fts_and_manifest_boundaries_apply_before_ranking() {
    let (_temp, root, store) = tests::fixture();
    fs::create_dir(root.join("a")).unwrap();
    fs::create_dir(root.join("z")).unwrap();
    for index in 0..110 {
        fs::write(
            root.join(format!("a/{index}.ts")),
            format!("function common{index}() {{}}\n"),
        )
        .unwrap();
    }
    fs::write(
        root.join("z/source.ts"),
        "function common() {} function caller() { common(); }\n",
    )
    .unwrap();
    fs::write(root.join("z/package.json"), "{}\n").unwrap();
    let found = run(
        &store,
        &root,
        "search_symbols",
        ContextQuery {
            query: "common".into(),
            scope: Some("z".into()),
            ..Default::default()
        },
    );
    assert_eq!(found["results"][0]["path"], "z/source.ts");
    let before = found["generation"].clone();
    fs::remove_file(root.join("z/package.json")).unwrap();
    assert_eq!(
        run(&store, &root, "check_index", ContextQuery::default())["freshness"],
        "stale"
    );
    assert_ne!(
        before,
        run(&store, &root, "repo_overview", ContextQuery::default())["generation"]
    );
}

#[test]
fn relative_extensions_namespace_aliases_and_python_packages_resolve() {
    let (_temp, root, store) = tests::fixture();
    fs::write(root.join("utils.ts"), "export function work() {}\n").unwrap();
    fs::write(
        root.join("main.ts"),
        "import * as utility from './utils.js';\nfunction caller() { utility.work(); }\n",
    )
    .unwrap();
    fs::write(
        root.join("external.ts"),
        "import { work } from 'external-utils';\nfunction externalCaller() { work(); }\n",
    )
    .unwrap();
    fs::write(root.join("helpers.py"), "def help_me(): pass\n").unwrap();
    fs::write(
        root.join("main.py"),
        "from .helpers import help_me\ndef python_caller():\n    help_me()\n",
    )
    .unwrap();
    for (caller, target) in [("caller", "work"), ("python_caller", "help_me")] {
        let trace = run(
            &store,
            &root,
            "trace_calls",
            ContextQuery {
                symbol: Some(caller.into()),
                direction: Some("out".into()),
                ..Default::default()
            },
        );
        assert!(
            trace["results"]
                .as_array()
                .unwrap()
                .iter()
                .any(|r| r["relation"] == "calls" && r["target"]["name"] == target),
            "{trace}"
        );
    }
    let external = run(
        &store,
        &root,
        "trace_calls",
        ContextQuery {
            symbol: Some("externalCaller".into()),
            direction: Some("out".into()),
            ..Default::default()
        },
    );
    assert!(
        external["results"].as_array().unwrap().is_empty(),
        "{external}"
    );
}
