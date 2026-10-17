//! Rebuildable Rust syntax facts. No semantic call graph, embeddings or writes
//! to source files. Explicit initialization exports a local `.flow/` map.
//! Every query is scoped by the engine's workspace.
mod project;
use history::{ContentId, HistoryStore};
pub use project::initialize;
use protocol::ContextQuery;
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs,
    io::Read,
    path::Path,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio_util::sync::CancellationToken;

pub const PARSER_VERSION: &str = "tree-sitter-0.26.13/rust-0.24.2/query-2";
const MAX_FILE: usize = 512 * 1024;
const MAX_TOTAL: usize = 8 * 1024 * 1024;
const MAX_FILES: usize = 1024;
type Result<T> = std::result::Result<T, history::Error>;
fn invalid(message: &'static str) -> history::Error {
    history::Error::Invalid(message)
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Symbol {
    name: String,
    kind: String,
    signature: String,
    start_line: usize,
    end_line: usize,
    start_byte: usize,
    end_byte: usize,
    parent: Option<String>,
    confidence: String,
}
#[derive(Clone, Serialize, Deserialize)]
struct File {
    #[serde(default)]
    stamp: Option<Stamp>,
    hash: String,
    source: String,
    symbols: Vec<Symbol>,
}

/// Content-addressed facts remain authoritative. Change-time stamps only reuse
/// old file bytes on Unix; racy timestamps and other hosts retain hash reads.
#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
struct Stamp {
    bytes: u64,
    modified_ns: u128,
    changed_ns: u128,
    device: u64,
    inode: u64,
}
impl Stamp {
    fn stable(&self) -> bool {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let guard = Duration::from_secs(2).as_nanos();
        self.inode != 0
            && now.saturating_sub(self.modified_ns) > guard
            && now.saturating_sub(self.changed_ns) > guard
    }
}
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct IndexStats {
    source_reads: usize,
    parsed_files: usize,
    reused_files: usize,
    updated_files: usize,
    removed_files: usize,
    symbol_rows_written: usize,
}

#[cfg(unix)]
fn stamp(root: &Path, path: &str) -> Result<Option<Stamp>> {
    use std::os::unix::fs::MetadataExt;
    let meta = fs::metadata(checked_path(root, path)?)?;
    if !meta.is_file() || meta.len() > MAX_FILE as u64 {
        return Err(invalid("context file too large or not regular"));
    }
    let Some(modified_ns) = meta
        .modified()
        .ok()
        .and_then(|time| time.duration_since(UNIX_EPOCH).ok())
        .map(|time| time.as_nanos())
    else {
        return Ok(None);
    };
    let (Ok(seconds), Ok(nanos)) = (
        u128::try_from(meta.ctime()),
        u128::try_from(meta.ctime_nsec()),
    ) else {
        return Ok(None);
    };
    Ok(Some(Stamp {
        bytes: meta.len(),
        modified_ns,
        changed_ns: seconds * 1_000_000_000 + nanos,
        device: meta.dev(),
        inode: meta.ino(),
    }))
}
#[cfg(not(unix))]
fn stamp(_root: &Path, _path: &str) -> Result<Option<Stamp>> {
    Ok(None)
}

fn check(cancel: &CancellationToken, started: Instant) -> Result<()> {
    if cancel.is_cancelled() || started.elapsed() > Duration::from_secs(5) {
        Err(invalid(
            "context refresh cancelled or exceeded five seconds",
        ))
    } else {
        Ok(())
    }
}
fn checked_path(root: &Path, path: &str) -> Result<std::path::PathBuf> {
    if path.is_empty()
        || Path::new(path)
            .components()
            .any(|c| !matches!(c, std::path::Component::Normal(_)))
    {
        return Err(invalid("invalid context path"));
    }
    let mut full = root.to_path_buf();
    for part in Path::new(path).components() {
        full.push(part);
        if fs::symlink_metadata(&full)?.file_type().is_symlink() {
            return Err(invalid("context symlinks are excluded"));
        }
    }
    if !full.canonicalize()?.starts_with(root) {
        return Err(invalid("context path escaped workspace"));
    }
    Ok(full)
}
fn source(root: &Path, path: &str) -> Result<String> {
    let full = checked_path(root, path)?;
    let mut bytes = Vec::new();
    fs::File::open(full)?
        .take((MAX_FILE + 1) as u64)
        .read_to_end(&mut bytes)?;
    if bytes.len() > MAX_FILE {
        return Err(invalid("context file too large"));
    }
    String::from_utf8(bytes).map_err(|_| invalid("context requires UTF-8"))
}
fn extract(text: &str, cancel: &CancellationToken, started: Instant) -> Result<Vec<Symbol>> {
    let mut parser = tree_sitter::Parser::new();
    parser
        .set_language(&tree_sitter_rust::LANGUAGE.into())
        .map_err(|_| invalid("Rust grammar mismatch"))?;
    let mut progress = |_: &tree_sitter::ParseState| {
        if check(cancel, started).is_err() {
            std::ops::ControlFlow::Break(())
        } else {
            std::ops::ControlFlow::Continue(())
        }
    };
    let tree = parser
        .parse_with_options(
            &mut |byte, _| &text.as_bytes()[byte..],
            None,
            Some(tree_sitter::ParseOptions::new().progress_callback(&mut progress)),
        )
        .ok_or_else(|| invalid("context parsing cancelled"))?;
    let mut out = Vec::new();
    let mut stack = vec![(tree.root_node(), None)];
    while let Some((node, parent)) = stack.pop() {
        check(cancel, started)?;
        if out.len() >= 4096 {
            return Err(invalid("too many symbols in file"));
        }
        let mut next_parent = parent.clone();
        if matches!(
            node.kind(),
            "function_item"
                | "function_signature_item"
                | "struct_item"
                | "enum_item"
                | "trait_item"
                | "type_item"
                | "const_item"
                | "static_item"
                | "mod_item"
                | "macro_definition"
                | "use_declaration"
                | "impl_item"
        ) {
            let name = node
                .child_by_field_name("name")
                .or_else(|| node.child_by_field_name("type"))
                .map(|n| &text[n.byte_range()])
                .unwrap_or(node.kind());
            let end = node
                .child_by_field_name("body")
                .map(|n| n.start_byte())
                .unwrap_or(node.end_byte());
            let signature: String = text[node.start_byte()..end].chars().take(512).collect();
            out.push(Symbol {
                name: name.into(),
                kind: node.kind().into(),
                signature,
                start_line: node.start_position().row + 1,
                end_line: node.end_position().row + 1,
                start_byte: node.start_byte(),
                end_byte: node.end_byte(),
                parent,
                confidence: if node.has_error() {
                    "partialSyntax"
                } else {
                    "syntaxOnly"
                }
                .into(),
            });
            next_parent = Some(format!("{}:{}", name, node.start_position().row + 1));
        }
        let mut cursor = node.walk();
        let children: Vec<_> = node.named_children(&mut cursor).collect();
        for child in children.into_iter().rev() {
            stack.push((child, next_parent.clone()));
        }
    }
    Ok(out)
}
fn schema(db: &Connection) -> Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS generations (workspace TEXT PRIMARY KEY, generation TEXT NOT NULL, parser TEXT NOT NULL, truncated INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS files (workspace TEXT NOT NULL, path TEXT NOT NULL, hash TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(workspace,path));
        CREATE VIRTUAL TABLE IF NOT EXISTS symbols USING fts5(workspace UNINDEXED,path,name,signature,data UNINDEXED, tokenize='unicode61');")?;
    Ok(())
}

fn walk(root: &Path) -> ignore::Walk {
    ignore::WalkBuilder::new(root)
        .parents(false)
        .git_global(false)
        .follow_links(false)
        .hidden(true)
        .max_depth(Some(64))
        .sort_by_file_path(|a, b| a.cmp(b))
        .build()
}

fn identity(workspace: &str, root: &Path) -> String {
    format!(
        "{workspace}:{}",
        ContentId::hash(root.as_os_str().as_encoded_bytes()).as_str()
    )
}

pub fn query(
    store: &HistoryStore,
    workspace: &str,
    root: &Path,
    method: &str,
    request: &ContextQuery,
    cancel: &CancellationToken,
) -> Result<Value> {
    query_with_stats(store, workspace, root, method, request, cancel).map(|(value, _)| value)
}
fn query_with_stats(
    store: &HistoryStore,
    workspace: &str,
    root: &Path,
    method: &str,
    request: &ContextQuery,
    cancel: &CancellationToken,
) -> Result<(Value, IndexStats)> {
    let mut stats = IndexStats::default();
    let started = Instant::now();
    if request.query.len() > 512 || request.path.as_ref().is_some_and(|p| p.len() > 4096) {
        return Err(invalid("context request too large"));
    }
    let root = root.canonicalize()?;
    let mut db = store.context_database()?;
    schema(&db)?;
    // One transaction publishes both file facts and FTS rows. Readers never see
    // a mixture of generations. The cache identity includes the canonical root.
    let identity = identity(workspace, &root);
    let tx = db.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
    let old_parser: Option<String> = tx
        .query_row(
            "SELECT parser FROM generations WHERE workspace=?",
            [&identity],
            |r| r.get(0),
        )
        .ok();
    let mut old = BTreeMap::new();
    let mut cached_paths = std::collections::BTreeSet::new();
    if old_parser.as_deref() == Some(PARSER_VERSION) {
        let mut stmt = tx.prepare("SELECT path,data FROM files WHERE workspace=?")?;
        for row in stmt.query_map([&identity], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })? {
            let (path, data) = row?;
            cached_paths.insert(path.clone());
            if let Ok(file) = serde_json::from_str::<File>(&data)
                && file.source.len() <= MAX_FILE
                && ContentId::hash(file.source.as_bytes()).as_str() == file.hash
                && file.symbols.iter().all(|symbol| {
                    symbol.start_byte <= symbol.end_byte
                        && symbol.end_byte <= file.source.len()
                        && file.source.is_char_boundary(symbol.start_byte)
                        && file.source.is_char_boundary(symbol.end_byte)
                })
            {
                old.insert(path, file);
            }
        }
    }
    let mut files = BTreeMap::new();
    let mut bytes = 0;
    let mut truncated = false;
    let walker = walk(&root);
    for entry in walker {
        check(cancel, started)?;
        let entry = match entry {
            Ok(e) => e,
            Err(_) => {
                truncated = true;
                continue;
            }
        };
        if !entry.file_type().is_some_and(|t| t.is_file())
            || entry.path().extension().is_none_or(|e| e != "rs")
        {
            continue;
        }
        if files.len() == MAX_FILES {
            truncated = true;
            break;
        }
        let Some(path) = entry.path().strip_prefix(&root).ok().and_then(Path::to_str) else {
            truncated = true;
            continue;
        };
        let path = path.replace(std::path::MAIN_SEPARATOR, "/");
        let before = match stamp(&root, &path) {
            Ok(value) => value,
            Err(_) => {
                truncated = true;
                continue;
            }
        };
        let cached = old.get(&path);
        let reuse = cached
            .is_some_and(|file| before.as_ref().is_some_and(Stamp::stable) && file.stamp == before);
        let file = if reuse {
            stats.reused_files += 1;
            cached.unwrap().clone()
        } else {
            let text = match source(&root, &path) {
                Ok(text) => text,
                Err(_) => {
                    truncated = true;
                    continue;
                }
            };
            stats.source_reads += 1;
            let after = stamp(&root, &path)?;
            if before != after {
                return Err(invalid("context file changed during refresh; retry"));
            }
            let hash = ContentId::hash(text.as_bytes()).as_str().to_owned();
            let symbols = if let Some(file) = cached.filter(|file| file.hash == hash) {
                stats.reused_files += 1;
                file.symbols.clone()
            } else {
                stats.parsed_files += 1;
                extract(&text, cancel, started)?
            };
            File {
                stamp: after,
                source: text,
                hash,
                symbols,
            }
        };
        bytes += file.source.len();
        if bytes > MAX_TOTAL {
            truncated = true;
            break;
        }
        files.insert(path.to_owned(), file);
    }
    let fingerprint: Vec<_> = files.iter().map(|(p, f)| (p, &f.hash)).collect();
    let generation =
        ContentId::hash(&serde_json::to_vec(&(PARSER_VERSION, &fingerprint, truncated)).unwrap())
            .as_str()
            .to_owned();
    let previous: Option<String> = tx
        .query_row(
            "SELECT generation FROM generations WHERE workspace=?",
            [&identity],
            |r| r.get(0),
        )
        .ok();
    if old_parser.as_deref() != Some(PARSER_VERSION) {
        tx.execute("DELETE FROM files WHERE workspace=?", [&identity])?;
        tx.execute("DELETE FROM symbols WHERE workspace=?", [&identity])?;
    }
    for path in cached_paths
        .iter()
        .filter(|path| !files.contains_key(*path))
    {
        check(cancel, started)?;
        tx.execute(
            "DELETE FROM files WHERE workspace=? AND path=?",
            params![identity, path],
        )?;
        tx.execute(
            "DELETE FROM symbols WHERE workspace=? AND path=?",
            params![identity, path],
        )?;
        stats.removed_files += 1;
    }
    for (path, file) in &files {
        check(cancel, started)?;
        let prior = old.get(path);
        let changed = prior.is_none_or(|prior| prior.hash != file.hash);
        if changed || prior.is_some_and(|prior| prior.stamp != file.stamp) {
            tx.execute(
                "INSERT OR REPLACE INTO files VALUES (?,?,?,?)",
                params![
                    identity,
                    path,
                    file.hash,
                    serde_json::to_string(file).unwrap()
                ],
            )?;
        }
        if changed {
            stats.updated_files += 1;
            tx.execute(
                "DELETE FROM symbols WHERE workspace=? AND path=?",
                params![identity, path],
            )?;
            for symbol in &file.symbols {
                tx.execute(
                    "INSERT INTO symbols VALUES (?,?,?,?,?)",
                    params![
                        identity,
                        path,
                        symbol.name,
                        symbol.signature,
                        serde_json::to_string(symbol).unwrap()
                    ],
                )?;
                stats.symbol_rows_written += 1;
            }
        }
    }
    if previous.as_ref() != Some(&generation) || old_parser.as_deref() != Some(PARSER_VERSION) {
        tx.execute(
            "INSERT OR REPLACE INTO generations VALUES (?,?,?,?)",
            params![identity, generation, PARSER_VERSION, truncated],
        )?;
    }
    check(cancel, started)?;
    tx.commit()?;
    let budget = request
        .byte_budget
        .unwrap_or(16384)
        .min(32768)
        .min(request.token_budget.unwrap_or(8192));
    // One byte per token is a conservative upper bound, not an exact tokenizer.
    if budget < 1024 {
        return Err(invalid("context budget must be at least 1024 bytes/tokens"));
    }
    let index_incomplete = truncated;
    let mut rows = Vec::new();
    let mut remaining = budget - 896;
    let mut returned = std::collections::BTreeSet::new();
    let mut append = |value: Value| {
        let size = serde_json::to_vec(&value).unwrap().len() + 1;
        if size > remaining {
            truncated = true;
        } else {
            remaining -= size;
            if let Some(path) = value["path"].as_str() {
                returned.insert(path.to_owned());
            }
            rows.push(value);
        }
    };
    let mut stale = false;
    if method == "get_symbol_context" || method == "check_context_freshness" {
        let path = request
            .path
            .as_ref()
            .ok_or_else(|| invalid("select a context path"))?;
        let file = files
            .get(path)
            .ok_or_else(|| invalid("path is excluded or not Rust"))?;
        stale = request
            .content_hash
            .as_ref()
            .is_some_and(|h| h != &file.hash);
        if method == "get_symbol_context" && !stale {
            let line = request.line.unwrap_or(1);
            let symbol = file
                .symbols
                .iter()
                .filter(|s| s.start_line <= line && s.end_line >= line)
                .min_by_key(|s| s.end_byte - s.start_byte);
            if let Some(symbol) = symbol {
                append(
                    json!({"path":path,"contentHash":file.hash,"symbol":symbol,"text":&file.source[symbol.start_byte..symbol.end_byte]}),
                );
            }
        } else {
            append(json!({"path":path,"contentHash":file.hash}));
        }
    } else if method == "search_symbols" {
        let terms: Vec<_> = request
            .query
            .split(|c: char| !c.is_alphanumeric())
            .filter(|s| !s.is_empty())
            .take(12)
            .map(|s| format!("\"{s}\"*"))
            .collect();
        if !terms.is_empty() {
            let mut stmt = db.prepare("SELECT path,data FROM symbols WHERE symbols MATCH ? AND workspace=? ORDER BY CASE WHEN lower(name)=lower(?) THEN 0 WHEN lower(path)=lower(?) THEN 1 ELSE 2 END,bm25(symbols),path,name,rowid LIMIT 101")?;
            for row in stmt.query_map(
                params![terms.join(" OR "), identity, request.query, request.query],
                |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)),
            )? {
                let (path, data) = row?;
                append(
                    json!({"path":path,"contentHash":files[&path].hash,"symbol":serde_json::from_str::<Value>(&data).map_err(|_| invalid("context cache is corrupt"))?}),
                );
            }
        }
    } else if method == "repo_overview" {
        for (path, file) in &files {
            append(
                json!({"path":path,"contentHash":file.hash,"symbols":file.symbols.len(),"language":"Rust"}),
            );
        }
    } else {
        return Err(invalid("unknown context tool"));
    }
    for (path, file) in &files {
        check(cancel, started)?;
        let current = stamp(&root, path);
        let metadata_valid = current.as_ref().is_ok_and(|current| current == &file.stamp);
        if !metadata_valid {
            stale = true;
            break;
        }
        // Hash every returned search/body fact and any racy or unsupported stamp.
        // Older, untouched Unix files need only the change-time/inode check.
        if (method != "repo_overview" && returned.contains(path))
            || !file.stamp.as_ref().is_some_and(Stamp::stable)
        {
            stats.source_reads += 1;
            if !source(&root, path)
                .is_ok_and(|text| ContentId::hash(text.as_bytes()).as_str() == file.hash)
            {
                stale = true;
                break;
            }
        }
    }
    if !index_incomplete && !stale {
        let mut visible = std::collections::BTreeSet::new();
        for entry in walk(&root) {
            check(cancel, started)?;
            let entry = entry.map_err(|_| invalid("context inventory changed"))?;
            if entry.file_type().is_some_and(|t| t.is_file())
                && entry.path().extension().is_some_and(|e| e == "rs")
            {
                let relative = entry
                    .path()
                    .strip_prefix(&root)
                    .ok()
                    .and_then(Path::to_str)
                    .ok_or_else(|| invalid("invalid context path"))?;
                let relative = relative.replace(std::path::MAIN_SEPARATOR, "/");
                if !files.contains_key(&relative) {
                    stale = true;
                    break;
                }
                visible.insert(relative.to_owned());
            }
        }
        stale |= visible.len() != files.len();
    }
    let value = json!({"workspace":workspace,"generation":generation,"parser":PARSER_VERSION,"confidence":"syntaxOnly","freshness":if stale {"stale"} else {"verifiedAtRead"},"verification":if cfg!(unix) {"changeTimeInventoryAndResultHashes"} else {"allIndexedContentHashes"},"truncated":truncated,"budgetBytes":budget,"tokenUpperBound":budget,"ranking":"exact symbol/path then FTS5 lexical terms (OR prefixes); no embeddings","results":rows});
    if serde_json::to_vec(&value).unwrap().len() > budget {
        return Err(invalid("context envelope exceeds budget"));
    }
    Ok((value, stats))
}

#[cfg(test)]
mod tests;
