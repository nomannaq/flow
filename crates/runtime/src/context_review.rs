//! Optional local Git overlap inspection. No fetch, checkout, hooks or LLM call.
use history::HistoryStore;
use protocol::ContextQuery;
use serde_json::{Value, json};
use std::{
    collections::BTreeSet,
    path::{Component, Path},
    time::{Duration, Instant},
};
use tokio_util::sync::CancellationToken;

pub(super) fn query(
    store: &HistoryStore,
    workspace: &str,
    root: &Path,
    method: &str,
    request: &ContextQuery,
    cancel: &CancellationToken,
) -> history::Result<Value> {
    if request.paths.len() > 256
        || request.paths.iter().any(|p| {
            p.is_empty()
                || p.len() > 512
                || p.contains(['\\', '\n', '\r', '\0'])
                || !Path::new(p)
                    .components()
                    .all(|c| matches!(c, Component::Normal(_)))
        })
    {
        return Err(history::Error::Invalid("invalid review paths"));
    }
    let deadline = Instant::now() + Duration::from_secs(5);
    let git = |args: Vec<&str>| {
        crate::worktrees::io::git_bounded(root, args, deadline, cancel).map_err(|_| {
            history::Error::Invalid("local Git review failed, timed out or was cancelled")
        })
    };
    let parse = |bytes: Vec<u8>| -> history::Result<BTreeSet<String>> {
        let mut result = BTreeSet::new();
        for raw in bytes.split(|b| *b == 0).filter(|p| !p.is_empty()) {
            let path = std::str::from_utf8(raw)
                .map_err(|_| history::Error::Invalid("Git review requires UTF-8 paths"))?;
            if path.len() > 512
                || path.contains(['\\', '\n', '\r'])
                || !Path::new(path)
                    .components()
                    .all(|p| matches!(p, Component::Normal(_)))
            {
                return Err(history::Error::Invalid("Git review path is unsupported"));
            }
            result.insert(path.into());
            if result.len() > 4096 {
                return Err(history::Error::Invalid(
                    "Git review exceeds 4096 changed paths",
                ));
            }
        }
        Ok(result)
    };
    let mut paths: BTreeSet<String> = request.paths.iter().cloned().collect();
    if paths.is_empty() {
        paths.extend(parse(git(vec![
            "diff",
            "HEAD",
            "--name-only",
            "-z",
            "--no-renames",
            "--no-ext-diff",
            "--no-textconv",
            "--",
        ])?)?);
        paths.extend(parse(git(vec![
            "ls-files",
            "--others",
            "--exclude-standard",
            "-z",
        ])?)?);
    }
    if method == "review_learnings" {
        let mut selected = request.clone();
        selected.paths = paths.iter().take(256).cloned().collect();
        let mut result = context::query(store, workspace, root, method, &selected, cancel)?;
        result["changedPathCount"] = json!(paths.len());
        if paths.len() > 256 {
            result["truncated"] = json!(true);
        }
        return Ok(result);
    }
    if request.query.is_empty() {
        let raw = git(vec![
            "for-each-ref",
            "--count=65",
            "--format=%(refname:short)",
            "refs/heads/",
        ])?;
        let branches = std::str::from_utf8(&raw)
            .map_err(|_| history::Error::Invalid("Git branches require UTF-8"))?;
        let mut rows: Vec<_> = branches
            .lines()
            .take(64)
            .map(|b| json!({"name":b,"kind":"localBranch"}))
            .collect();
        let budget = request
            .byte_budget
            .unwrap_or(16384)
            .min(request.token_budget.unwrap_or(32768))
            .clamp(1024, 32768);
        while serde_json::to_vec(&rows).unwrap().len() + 512 > budget {
            rows.pop();
        }
        return Ok(
            json!({"freshness":"observedAtRead","truncated":branches.lines().count()>rows.len(),"results":rows,"note":"Select a local branch to inspect overlapping paths; no remote refresh is performed"}),
        );
    }
    if request.query.len() > 200
        || request.query.starts_with('-')
        || request.query.contains(['\0', '\n', '\r'])
    {
        return Err(history::Error::Invalid("invalid local branch name"));
    }
    let branch = format!(
        "refs/heads/{}^{{commit}}",
        request.query.trim_start_matches("refs/heads/")
    );
    let target = String::from_utf8(git(vec![
        "rev-parse",
        "--verify",
        "--end-of-options",
        &branch,
    ])?)
    .map_err(|_| history::Error::Invalid("invalid branch commit"))?;
    let target = target.trim();
    let valid_hash =
        |id: &str| matches!(id.len(), 40 | 64) && id.bytes().all(|b| b.is_ascii_hexdigit());
    if !valid_hash(target) {
        return Err(history::Error::Invalid("invalid branch commit"));
    }
    let head = String::from_utf8(git(vec!["rev-parse", "--verify", "HEAD^{commit}"])?)
        .map_err(|_| history::Error::Invalid("invalid current commit"))?;
    let head = head.trim();
    if !valid_hash(head) {
        return Err(history::Error::Invalid("invalid current commit"));
    }
    let base = String::from_utf8(git(vec!["merge-base", head, target])?)
        .map_err(|_| history::Error::Invalid("no local merge base"))?;
    let base = base.trim();
    if !valid_hash(base) {
        return Err(history::Error::Invalid("invalid merge base"));
    }
    paths.extend(parse(git(vec![
        "diff",
        base,
        head,
        "--name-only",
        "-z",
        "--no-renames",
        "--no-ext-diff",
        "--no-textconv",
        "--",
    ])?)?);
    let other = parse(git(vec![
        "diff",
        base,
        target,
        "--name-only",
        "-z",
        "--no-renames",
        "--no-ext-diff",
        "--no-textconv",
        "--",
    ])?)?;
    let overlaps: Vec<_> = paths.intersection(&other).collect();
    let mut rows:Vec<_>=overlaps.iter().take(100).map(|p|json!({"path":p,"name":p,"kind":"branchOverlap","text":format!("Both the current work and local branch {} change this path. Inspect the diff to determine compatibility.",request.query)})).collect();
    let budget = request.byte_budget.unwrap_or(16384).clamp(1024, 32768);
    while serde_json::to_vec(&rows).unwrap().len() + 512 > budget {
        rows.pop();
    }
    Ok(
        json!({"freshness":"observedAtRead","branch":request.query,"currentCommit":head,"commit":target,"mergeBase":base,"overlapCount":overlaps.len(),"truncated":rows.len()<overlaps.len(),"results":rows,"conflictDetection":"path overlap only; does not prove a semantic conflict","remoteFetch":false}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_repository::Repository;
    #[test]
    fn local_branch_overlaps_and_review_never_modify_git_state() {
        let repo = Repository::new();
        repo.git(&["checkout", "-b", "other"]);
        repo.write("tracked.txt", b"other\n");
        repo.git(&["commit", "-am", "other"]);
        repo.git(&["checkout", "main"]);
        repo.write("tracked.txt", b"ours\n");
        let before = repo.git(&["status", "--porcelain=v1"]);
        let head = repo.git(&["rev-parse", "HEAD"]);
        let store = HistoryStore::open(&repo.root, &repo.data).unwrap();
        let cancel = CancellationToken::new();
        let value = query(
            &store,
            "project",
            &repo.root,
            "branch_overlap",
            &ContextQuery {
                query: "other".into(),
                ..Default::default()
            },
            &cancel,
        )
        .unwrap();
        assert_eq!(value["overlapCount"], 1);
        assert_eq!(value["results"][0]["path"], "tracked.txt");
        assert_eq!(value["remoteFetch"], false);
        context::save_learning(
            &store,
            &repo.root,
            &protocol::LearningWrite {
                title: "Keep format".into(),
                author: "Reviewer".into(),
                body: "Keep the line-oriented format.".into(),
                touches: vec!["tracked.txt".into()],
                ..Default::default()
            },
            &cancel,
        )
        .unwrap();
        let review = query(
            &store,
            "project",
            &repo.root,
            "review_learnings",
            &ContextQuery::default(),
            &cancel,
        )
        .unwrap();
        assert_eq!(review["results"][0]["title"], "Keep format");
        assert_eq!(head, repo.git(&["rev-parse", "HEAD"]));
        // Saving a reviewed note is the only new user-visible file.
        assert_eq!(
            before,
            repo.git(&["status", "--porcelain=v1", "--", "tracked.txt"])
        );
        cancel.cancel();
        assert!(
            query(
                &store,
                "project",
                &repo.root,
                "branch_overlap",
                &ContextQuery {
                    query: "other".into(),
                    ..Default::default()
                },
                &cancel
            )
            .is_err()
        );
    }
}
