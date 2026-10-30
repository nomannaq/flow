//! Conservative static relationships. Ambiguous/dynamic targets stay unresolved.
use super::*;
use std::collections::{BTreeSet, VecDeque};

pub(super) const METHODS: &[&str] = &[
    "find_code",
    "find_all",
    "get_file",
    "file_api",
    "trace_calls",
    "repo_map",
    "get_graph",
];
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Node {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub path: String,
    pub language: String,
    pub scope: String,
    pub signature: String,
    pub start_line: usize,
    pub end_line: usize,
    pub start_byte: usize,
    pub end_byte: usize,
    pub parent: Option<String>,
    pub content_hash: String,
    pub confidence: String,
    pub arity: Option<usize>,
    pub exported: Option<bool>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Edge {
    pub source: String,
    pub target: String,
    pub relation: String,
    pub confidence: String,
    pub line: usize,
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Graph {
    pub nodes: Vec<Node>,
    pub edges: Vec<Edge>,
    pub unresolved: usize,
    pub truncated: bool,
    pub scopes: Vec<String>,
}
pub(super) fn within(path: &str, prefix: &str) -> bool {
    prefix.is_empty()
        || path == prefix
        || path
            .strip_prefix(prefix)
            .is_some_and(|rest| rest.starts_with('/'))
}
fn scope_of(path: &str, scopes: &BTreeSet<String>) -> String {
    scopes
        .iter()
        .filter(|p| within(path, p))
        .max_by_key(|p| p.len())
        .cloned()
        .unwrap_or_default()
}
fn family(language: &str) -> &str {
    if matches!(language, "JavaScript" | "TypeScript" | "TSX" | "Vue") {
        "javascript"
    } else {
        language
    }
}
fn display(node: &Node) -> Value {
    json!({"id":node.id,"path":node.path,"name":node.name,"kind":node.kind,"language":node.language,"scope":node.scope,
        "signature":node.signature,"startLine":node.start_line,"endLine":node.end_line,"parent":node.parent,"contentHash":node.content_hash,"confidence":node.confidence,"exported":node.exported})
}

fn module_paths(
    module: &str,
    path: &str,
    files: &BTreeMap<String, File>,
    scope: &str,
) -> BTreeSet<String> {
    let mut module = module.trim_matches(['\'', '"', ';']).replace("::", "/");
    if module.starts_with('.') && !module.contains('/') {
        let dots = module.chars().take_while(|c| *c == '.').count();
        module = format!(
            "./{}{}",
            "../".repeat(dots.saturating_sub(1)),
            module[dots..].replace('.', "/")
        );
    }
    let directory = Path::new(path).parent().unwrap_or(Path::new(""));
    let mut parts: Vec<String> = if module.starts_with('.') {
        directory
            .iter()
            .filter_map(|p| p.to_str().map(str::to_owned))
            .collect()
    } else {
        Vec::new()
    };
    for piece in module.split('/') {
        match piece {
            "" | "." => {}
            ".." => {
                if parts.pop().is_none() {
                    return BTreeSet::new();
                }
            }
            p => parts.push(p.into()),
        }
    }
    let relative = parts.join("/");
    let relative = if syntax::grammar(&relative).is_some() {
        Path::new(&relative)
            .with_extension("")
            .to_string_lossy()
            .into_owned()
    } else {
        relative
    };
    let dotted = module.replace('.', "/");
    files
        .keys()
        .filter(|candidate| {
            if !scope.is_empty() && !module.starts_with('.') && !within(candidate, scope) {
                return false;
            }
            let stem = Path::new(candidate.as_str())
                .with_extension("")
                .to_string_lossy()
                .replace('\\', "/");
            stem == relative
                || stem == format!("{relative}/index")
                || stem == format!("{relative}/mod")
                || stem == format!("{relative}/__init__")
                || !module.starts_with('.')
                    && (stem.ends_with(&format!("/{dotted}"))
                        || stem == dotted
                        || module.starts_with("crate/")
                            && stem.ends_with(&format!("/{}", module.trim_start_matches("crate/")))
                        || module.rsplit('/').next().is_some_and(|tail| {
                            Path::new(stem.as_str())
                                .file_name()
                                .and_then(|s| s.to_str())
                                == Some(tail)
                        }))
        })
        .cloned()
        .collect()
}

fn build(
    files: &BTreeMap<String, File>,
    scopes: &BTreeSet<String>,
    cancel: &CancellationToken,
    started: Instant,
) -> Result<Graph> {
    let mut graph = Graph {
        nodes: Vec::new(),
        edges: Vec::new(),
        unresolved: 0,
        truncated: false,
        scopes: scopes.iter().cloned().collect(),
    };
    for (path, file) in files {
        check(cancel, started)?;
        let scope = scope_of(path, scopes);
        graph.nodes.push(Node {
            id: path.clone(),
            name: Path::new(path)
                .file_name()
                .unwrap()
                .to_string_lossy()
                .into(),
            kind: "file".into(),
            path: path.clone(),
            language: file.language.clone(),
            scope: scope.clone(),
            signature: String::new(),
            start_line: 1,
            end_line: file.source.lines().count().max(1),
            start_byte: 0,
            end_byte: file.source.len(),
            parent: None,
            content_hash: file.hash.clone(),
            confidence: "syntaxOnly".into(),
            arity: None,
            exported: Some(true),
        });
        let mut parents: Vec<usize> = Vec::new();
        for symbol in &file.symbols {
            if graph.nodes.len() >= 12000 {
                graph.truncated = true;
                break;
            }
            while parents
                .last()
                .is_some_and(|p| graph.nodes[*p].end_byte <= symbol.start_byte)
            {
                parents.pop();
            }
            let parent = parents
                .last()
                .map_or_else(|| path.clone(), |p| graph.nodes[*p].id.clone());
            let id = format!("{path}#{}", symbol.start_byte);
            graph.edges.push(Edge {
                source: parent.clone(),
                target: id.clone(),
                relation: "contains".into(),
                confidence: "syntax".into(),
                line: symbol.start_line,
            });
            parents.push(graph.nodes.len());
            graph.nodes.push(Node {
                id,
                name: symbol.name.clone(),
                kind: symbol.kind.clone(),
                path: path.clone(),
                language: file.language.clone(),
                scope: scope.clone(),
                signature: symbol.signature.clone(),
                start_line: symbol.start_line,
                end_line: symbol.end_line,
                start_byte: symbol.start_byte,
                end_byte: symbol.end_byte,
                parent: Some(parent),
                content_hash: file.hash.clone(),
                confidence: symbol.confidence.clone(),
                arity: symbol.arity,
                exported: symbol.exported,
            });
        }
        if graph.truncated {
            break;
        }
    }
    let by_id: BTreeMap<_, _> = graph
        .nodes
        .iter()
        .enumerate()
        .map(|(i, n)| (n.id.as_str(), i))
        .collect();
    let mut by_name: BTreeMap<&str, Vec<usize>> = BTreeMap::new();
    let mut by_file: BTreeMap<&str, Vec<usize>> = BTreeMap::new();
    for (i, node) in graph.nodes.iter().enumerate() {
        by_file.entry(&node.path).or_default().push(i);
        if node.kind != "file" {
            by_name.entry(&node.name).or_default().push(i);
        }
    }
    let mut edges = Vec::new();
    let mut seen = BTreeSet::new();
    for (path, file) in files {
        let Some(local) = by_file.get(path.as_str()) else {
            continue;
        };
        let scope = scope_of(path, scopes);
        for reference in &file.references {
            check(cancel, started)?;
            if graph.edges.len() + edges.len() >= 50000 {
                graph.truncated = true;
                break;
            }
            let source = local
                .iter()
                .map(|i| &graph.nodes[*i])
                .filter(|n| {
                    n.start_byte <= reference.start_byte && n.end_byte > reference.start_byte
                })
                .min_by_key(|n| (n.end_byte - n.start_byte, n.kind == "file"))
                .unwrap_or(&graph.nodes[local[0]]);
            let import = file.references.iter().find(|r| {
                r.relation == "imports"
                    && r.alias.as_deref().unwrap_or(&r.name)
                        == reference.qualifier.as_deref().unwrap_or(&reference.name)
            });
            let module = reference
                .module
                .as_deref()
                .or_else(|| import.and_then(|r| r.module.as_deref()));
            let imported_paths = module
                .map(|m| {
                    if family(&file.language) == "javascript" && !m.starts_with('.') {
                        BTreeSet::new()
                    } else {
                        module_paths(m, path, files, &scope)
                    }
                })
                .unwrap_or_default();
            let name = if reference.qualifier.is_none() {
                import.map_or(reference.name.as_str(), |r| r.name.as_str())
            } else {
                &reference.name
            };
            let mut candidates: Vec<_> = by_name
                .get(name)
                .into_iter()
                .flatten()
                .map(|i| &graph.nodes[*i])
                .filter(|n| {
                    family(&n.language) == family(&file.language)
                        && (module.is_some() || n.scope == scope)
                })
                .collect();
            let mut confidence = "inferred";
            // An explicit external import must never bind a coincidentally named
            // local symbol. Package managers and dynamic loaders need compiler help.
            if module.is_some() && imported_paths.is_empty() {
                candidates.clear();
            }
            if !imported_paths.is_empty() {
                candidates.retain(|n| imported_paths.contains(&n.path));
                confidence = "importBound";
            }
            if reference.relation == "imports" && candidates.is_empty() {
                candidates = imported_paths
                    .iter()
                    .filter_map(|p| by_id.get(p.as_str()).map(|i| &graph.nodes[*i]))
                    .collect();
            }
            if let Some(receiver) = &reference.qualifier {
                let binding = file
                    .bindings
                    .iter()
                    .filter(|b| {
                        b.name == *receiver
                            && b.scope_start <= reference.start_byte
                            && b.scope_end >= reference.start_byte
                            && b.start_byte <= reference.start_byte
                    })
                    .max_by_key(|b| b.start_byte);
                let mut receiver_type = binding.map(|b| b.type_name.as_str());
                let mut parent = source.parent.as_deref();
                if matches!(receiver.as_str(), "self" | "this" | "Self") {
                    while let Some(id) = parent {
                        let Some(node) = by_id.get(id).map(|i| &graph.nodes[*i]) else {
                            break;
                        };
                        if node.kind.contains("class")
                            || node.kind.contains("impl")
                            || node.kind.contains("struct")
                        {
                            receiver_type = Some(&node.name);
                            break;
                        }
                        parent = node.parent.as_deref();
                    }
                } else if receiver.chars().next().is_some_and(char::is_uppercase) {
                    receiver_type = Some(receiver);
                }
                if let Some(typ) = receiver_type {
                    candidates.retain(|n| {
                        n.parent
                            .as_ref()
                            .and_then(|p| by_id.get(p.as_str()))
                            .is_some_and(|p| graph.nodes[*p].name == typ)
                    });
                    confidence = "receiverBound";
                } else if imported_paths.is_empty() {
                    let paths = module_paths(receiver, path, files, &scope);
                    candidates.retain(|n| paths.contains(&n.path));
                    confidence = "moduleBound";
                }
            }
            if reference.relation == "calls"
                && let Some(arity) = reference.arity
            {
                let matching: Vec<_> = candidates
                    .iter()
                    .copied()
                    .filter(|n| n.arity.is_none_or(|a| a == arity))
                    .collect();
                candidates = matching;
            }
            let same_file: Vec<_> = candidates
                .iter()
                .copied()
                .filter(|n| n.path == *path)
                .collect();
            if !same_file.is_empty() {
                candidates = same_file;
                if confidence == "inferred" {
                    confidence = "sameFile";
                }
            }
            if candidates.len() > 1 {
                let same_parent: Vec<_> = candidates
                    .iter()
                    .copied()
                    .filter(|n| n.parent == source.parent)
                    .collect();
                if !same_parent.is_empty() {
                    candidates = same_parent;
                }
            }
            if candidates.len() == 1 {
                let target = candidates[0];
                if reference.relation == "references" && source.id == target.id {
                    continue;
                }
                let key = (
                    source.id.clone(),
                    target.id.clone(),
                    reference.relation.clone(),
                    reference.line,
                );
                if seen.insert(key) {
                    edges.push(Edge {
                        source: source.id.clone(),
                        target: target.id.clone(),
                        relation: reference.relation.clone(),
                        confidence: confidence.into(),
                        line: reference.line,
                    });
                }
            } else {
                graph.unresolved += 1;
            }
        }
    }
    graph.edges.extend(edges);
    Ok(graph)
}

pub(super) fn load(
    db: &Connection,
    identity: &str,
    generation: &str,
    files: &BTreeMap<String, File>,
    scopes: &BTreeSet<String>,
    cancel: &CancellationToken,
    started: Instant,
) -> Result<Graph> {
    let cached: Option<String> = db
        .query_row(
            "SELECT data FROM graphs WHERE workspace=? AND generation=?",
            params![identity, generation],
            |r| r.get(0),
        )
        .ok();
    if let Some(text) = cached.filter(|s| s.len() <= 24 * 1024 * 1024)
        && let Ok(graph) = serde_json::from_str::<Graph>(&text)
        && graph.nodes.len() <= 12000
        && graph.edges.len() <= 50000
    {
        let ids: BTreeSet<_> = graph.nodes.iter().map(|n| n.id.as_str()).collect();
        if ids.len() == graph.nodes.len()
            && graph.scopes == scopes.iter().cloned().collect::<Vec<_>>()
            && graph.nodes.iter().all(|n| {
                files.get(&n.path).is_some_and(|f| {
                    n.content_hash == f.hash
                        && n.start_byte <= n.end_byte
                        && n.end_byte <= f.source.len()
                        && f.source.is_char_boundary(n.start_byte)
                        && f.source.is_char_boundary(n.end_byte)
                })
            })
            && graph
                .edges
                .iter()
                .all(|e| ids.contains(e.source.as_str()) && ids.contains(e.target.as_str()))
        {
            return Ok(graph);
        }
    }
    let graph = build(files, scopes, cancel, started)?;
    check(cancel, started)?;
    let data = serde_json::to_string(&graph).unwrap();
    if data.len() <= 24 * 1024 * 1024 {
        db.execute(
            "INSERT OR REPLACE INTO graphs VALUES (?,?,?)",
            params![identity, generation, data],
        )?;
    }
    Ok(graph)
}

pub(super) fn terms(text: &str) -> Vec<String> {
    let mut spaced = String::new();
    let mut previous = ' ';
    for c in text.chars() {
        if c.is_uppercase() && previous.is_lowercase() {
            spaced.push(' ');
        }
        spaced.push(if c.is_alphanumeric() { c } else { ' ' });
        previous = c;
    }
    spaced
        .split_whitespace()
        .map(str::to_lowercase)
        .filter(|s| {
            s.len() >= 2
                && ![
                    "the",
                    "and",
                    "for",
                    "with",
                    "this",
                    "that",
                    "from",
                    "please",
                    "would",
                    "could",
                    "implement",
                    "change",
                    "update",
                    "fix",
                ]
                .contains(&s.as_str())
        })
        .take(32)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

pub(super) fn query(
    graph: &Graph,
    files: &BTreeMap<String, File>,
    method: &str,
    request: &ContextQuery,
    cancel: &CancellationToken,
    started: Instant,
) -> Result<Vec<Value>> {
    let prefix = request.scope.as_deref().unwrap_or("").trim_end_matches('/');
    if !prefix.is_empty()
        && Path::new(prefix)
            .components()
            .any(|p| !matches!(p, std::path::Component::Normal(_)))
    {
        return Err(invalid("invalid context scope"));
    }
    let limit = request.limit.unwrap_or(100);
    if !(1..=200).contains(&limit) {
        return Err(invalid("context result limit must be 1–200"));
    }
    let nodes: Vec<_> = graph
        .nodes
        .iter()
        .filter(|n| within(&n.path, prefix))
        .collect();
    let ids: BTreeSet<_> = nodes.iter().map(|n| n.id.as_str()).collect();
    let edges: Vec<_> = graph
        .edges
        .iter()
        .filter(|e| ids.contains(e.source.as_str()) && ids.contains(e.target.as_str()))
        .collect();
    let by_id: BTreeMap<_, _> = nodes.iter().map(|n| (n.id.as_str(), *n)).collect();
    let mut coupling: BTreeMap<&str, usize> = BTreeMap::new();
    for edge in &edges {
        if edge.relation != "contains" {
            *coupling.entry(&edge.target).or_default() += 1;
        }
    }
    match method {
        "get_file" => {
            let path = request.path.as_deref().unwrap_or(&request.query);
            let file = files
                .get(path)
                .filter(|_| within(path, prefix))
                .ok_or_else(|| invalid("select an indexed file"))?;
            let line = request.line.unwrap_or(1).max(1);
            let selected: Vec<_> = file.source.lines().skip(line - 1).take(100).collect();
            let text = selected.join("\n");
            let excerpt: String = text.chars().take(8192).collect();
            Ok(vec![
                json!({"path":path,"language":file.language,"contentHash":file.hash,"startLine":line,"text":excerpt,"sourceTruncated":line>1||text.len()>excerpt.len()||line+selected.len()<=file.source.lines().count()}),
            ])
        }
        "file_api" => {
            let requested = request.path.as_deref().unwrap_or(&request.query);
            let paths: BTreeSet<_> = nodes
                .iter()
                .filter(|n| {
                    n.kind == "file"
                        && (n.path == requested
                            || Path::new(&n.path).file_name().and_then(|p| p.to_str())
                                == Some(requested))
                })
                .map(|n| n.path.as_str())
                .collect();
            if paths.len() != 1 {
                return Err(invalid("select one unambiguous indexed file"));
            }
            Ok(nodes
                .into_iter()
                .filter(|n| paths.contains(n.path.as_str()) && n.kind != "file")
                .take(limit + 1)
                .map(display)
                .collect())
        }
        "find_code" => {
            let terms = terms(&request.query);
            if terms.is_empty() {
                return Ok(Vec::new());
            }
            let mut scores: BTreeMap<&str, f64> = BTreeMap::new();
            for node in &nodes {
                check(cancel, started)?;
                let name = node.name.to_lowercase();
                let path = node.path.to_lowercase();
                let signature = node.signature.to_lowercase();
                let body: String = files[&node.path].source[node.start_byte..node.end_byte]
                    .chars()
                    .take(4096)
                    .collect::<String>()
                    .to_lowercase();
                let mut score = if name == request.query.to_lowercase() {
                    200.0
                } else if path == request.query.to_lowercase() {
                    150.0
                } else {
                    0.0
                };
                for term in &terms {
                    score += if name.contains(term) {
                        20.0
                    } else if signature.contains(term) {
                        8.0
                    } else if path.contains(term) {
                        5.0
                    } else if body.contains(term) {
                        1.0
                    } else {
                        0.0
                    };
                }
                if node.kind == "file" {
                    score *= 0.3;
                }
                if score > 0.0 {
                    scores.insert(&node.id, score);
                }
            }
            // A small, deterministic relevance propagation surfaces related siblings.
            // It never adds a global hub without a lexical/neighbor seed.
            let seeds = scores.clone();
            for _ in 0..3 {
                let prior = scores.clone();
                scores = seeds.clone();
                for edge in &edges {
                    if edge.relation == "contains" {
                        continue;
                    }
                    if let Some(score) = prior.get(edge.source.as_str()) {
                        *scores.entry(&edge.target).or_default() += score * 0.1
                            / (1 + coupling.get(edge.target.as_str()).copied().unwrap_or(0)) as f64;
                    }
                    if let Some(score) = prior.get(edge.target.as_str()) {
                        *scores.entry(&edge.source).or_default() += score * 0.1;
                    }
                }
            }
            let mut ranked: Vec<_> = nodes
                .into_iter()
                .filter_map(|n| scores.get(n.id.as_str()).map(|s| (n, *s)))
                .collect();
            ranked.sort_by(|a, b| {
                b.1.total_cmp(&a.1)
                    .then(a.0.path.cmp(&b.0.path))
                    .then(a.0.start_line.cmp(&b.0.start_line))
            });
            let mut per_file = BTreeMap::<&str, usize>::new();
            Ok(ranked
                .into_iter()
                .filter(|(n, _)| {
                    let count = per_file.entry(&n.path).or_default();
                    *count += 1;
                    *count <= 3
                })
                .take(limit + 1)
                .map(|(node, score)| {
                    let mut row = display(node);
                    let text = &files[&node.path].source[node.start_byte..node.end_byte];
                    let cap = if request.full { 8192 } else { 1200 };
                    let source: String = if request.full {
                        text.chars().take(cap).collect()
                    } else {
                        text.lines()
                            .take(8)
                            .collect::<Vec<_>>()
                            .join("\n")
                            .chars()
                            .take(cap)
                            .collect()
                    };
                    row["text"] = json!(source);
                    row["sourceTruncated"] = json!(source.len() < text.len());
                    row["score"] = json!(score);
                    row["incoming"] = json!(coupling.get(node.id.as_str()).copied().unwrap_or(0));
                    row
                })
                .collect())
        }
        "find_all" => {
            let pattern = request.pattern.as_deref().unwrap_or(&request.query);
            if pattern.is_empty() {
                return Err(invalid("enter a context search pattern"));
            }
            let pattern = if request.fixed {
                regex::escape(pattern)
            } else {
                pattern.to_owned()
            };
            let pattern = regex::RegexBuilder::new(&pattern)
                .case_insensitive(request.ignore_case)
                .size_limit(128 * 1024)
                .dfa_size_limit(256 * 1024)
                .build()
                .map_err(|_| invalid("invalid or oversized context pattern"))?;
            let mut hits = Vec::new();
            for (path, file) in files.iter().filter(|(p, _)| within(p, prefix)) {
                for (line, text) in file.source.lines().enumerate() {
                    check(cancel, started)?;
                    if pattern.is_match(text) {
                        let symbol = nodes
                            .iter()
                            .filter(|n| {
                                n.path == *path && n.start_line <= line + 1 && n.end_line > line
                            })
                            .min_by_key(|n| n.end_byte - n.start_byte);
                        hits.push(json!({"path":path,"contentHash":file.hash,"line":line+1,"text":text.chars().take(512).collect::<String>(),"symbol":symbol.map(|n|display(n)),"incoming":symbol.and_then(|n|coupling.get(n.id.as_str())).copied().unwrap_or(0)}));
                        if hits.len() > 1000 {
                            break;
                        }
                    }
                }
                if hits.len() > 1000 {
                    break;
                }
            }
            hits.sort_by(|a, b| {
                b["incoming"]
                    .as_u64()
                    .cmp(&a["incoming"].as_u64())
                    .then(a["path"].as_str().cmp(&b["path"].as_str()))
                    .then(a["line"].as_u64().cmp(&b["line"].as_u64()))
            });
            Ok(hits.into_iter().take(limit + 1).collect())
        }
        "trace_calls" => {
            let symbol = request.symbol.as_deref().unwrap_or(&request.query);
            let mut roots: Vec<_> = nodes
                .iter()
                .filter(|n| {
                    n.id == symbol
                        || n.name == symbol
                        || n.path == symbol
                        || n.parent
                            .as_ref()
                            .and_then(|p| by_id.get(p.as_str()))
                            .is_some_and(|p| {
                                format!("{}.{}", p.name, n.name) == symbol
                                    || format!("{}::{}", p.name, n.name) == symbol
                            })
                })
                .copied()
                .collect();
            if let (Some(path), Some(line)) = (&request.path, request.line) {
                roots = nodes
                    .iter()
                    .filter(|n| n.path == *path && n.start_line <= line && n.end_line >= line)
                    .min_by_key(|n| n.end_byte - n.start_byte)
                    .copied()
                    .into_iter()
                    .collect();
            }
            if roots.is_empty() {
                return Err(invalid("no matching indexed symbol"));
            }
            let direction = request.direction.as_deref().unwrap_or("in");
            if !matches!(direction, "in" | "out") {
                return Err(invalid("trace direction must be in or out"));
            }
            let depth = request.depth.unwrap_or(1);
            if !(1..=32).contains(&depth) {
                return Err(invalid("trace depth must be 1–32"));
            }
            let mut queue: VecDeque<_> = roots.iter().map(|n| (n.id.as_str(), 0)).collect();
            let mut visited: BTreeSet<_> = roots.iter().map(|n| n.id.as_str()).collect();
            let mut result = Vec::new();
            let mut reported = BTreeSet::new();
            while let Some((id, level)) = queue.pop_front() {
                check(cancel, started)?;
                if level == depth {
                    continue;
                }
                for edge in &edges {
                    if edge.relation == "contains" {
                        continue;
                    }
                    let next = if direction == "in" && edge.target == id {
                        edge.source.as_str()
                    } else if direction == "out" && edge.source == id {
                        edge.target.as_str()
                    } else {
                        continue;
                    };
                    if reported.insert((
                        edge.source.as_str(),
                        edge.target.as_str(),
                        edge.relation.as_str(),
                        edge.line,
                    )) {
                        result.push(json!({"source":display(by_id[edge.source.as_str()]),"target":display(by_id[edge.target.as_str()]),"relation":edge.relation,"confidence":edge.confidence,"line":edge.line,"distance":level+1}));
                    }
                    if visited.insert(next) {
                        queue.push_back((next, level + 1));
                    }
                    if result.len() > limit {
                        return Ok(result);
                    }
                }
            }
            Ok(result)
        }
        "get_graph" => {
            let mut selected = nodes;
            selected.sort_by(|a, b| {
                coupling
                    .get(b.id.as_str())
                    .cmp(&coupling.get(a.id.as_str()))
                    .then(a.path.cmp(&b.path))
                    .then(a.start_line.cmp(&b.start_line))
            });
            if !request.query.trim().is_empty() {
                let query = request.query.to_lowercase();
                let seeds: BTreeSet<_> = selected
                    .iter()
                    .filter(|n| {
                        n.name.to_lowercase().contains(&query)
                            || n.path.to_lowercase().contains(&query)
                    })
                    .map(|n| n.id.as_str())
                    .collect();
                let mut neighbors = seeds.clone();
                for edge in &edges {
                    if seeds.contains(edge.source.as_str()) || seeds.contains(edge.target.as_str())
                    {
                        neighbors.insert(&edge.source);
                        neighbors.insert(&edge.target);
                    }
                }
                selected.retain(|n| neighbors.contains(n.id.as_str()));
                selected.sort_by_key(|n| !seeds.contains(n.id.as_str()));
            }
            selected.truncate(limit);
            let keep: BTreeSet<_> = selected.iter().map(|n| n.id.as_str()).collect();
            let selected_edges: Vec<_> = edges
                .into_iter()
                .filter(|e| keep.contains(e.source.as_str()) && keep.contains(e.target.as_str()))
                .collect();
            let mut result = vec![
                json!({"type":"coverage","nodes":graph.nodes.len(),"edges":graph.edges.len(),"unresolved":graph.unresolved,"truncated":graph.truncated||keep.len()<ids.len()||selected_edges.len()>limit*2,"scopes":graph.scopes}),
            ];
            result.extend(selected.into_iter().map(|n| {
                let mut v = display(n);
                v["type"] = json!("node");
                v
            }));
            result.extend(
                selected_edges
                    .into_iter()
                    .take(limit * 2)
                    .map(|e| json!({"type":"edge","edge":e})),
            );
            Ok(result)
        }
        "repo_map" => {
            let mut directories: BTreeMap<String, Vec<&Node>> = BTreeMap::new();
            for node in nodes {
                directories
                    .entry(
                        Path::new(&node.path)
                            .parent()
                            .unwrap_or(Path::new(""))
                            .to_string_lossy()
                            .into(),
                    )
                    .or_default()
                    .push(node);
            }
            let mut directories: Vec<_> = directories.into_iter().collect();
            directories.sort_by(|a, b| {
                let count = |v: &Vec<&Node>| {
                    v.iter()
                        .map(|n| coupling.get(n.id.as_str()).copied().unwrap_or(0))
                        .sum::<usize>()
                };
                count(&b.1).cmp(&count(&a.1)).then(a.0.cmp(&b.0))
            });
            Ok(directories.into_iter().take(limit+1).map(|(directory,mut entries)| {
                entries.sort_by(|a,b|coupling.get(b.id.as_str()).cmp(&coupling.get(a.id.as_str())).then(a.id.cmp(&b.id)));
                json!({"directory":directory,"files":entries.iter().filter(|n|n.kind=="file").count(),"symbols":entries.iter().filter(|n|n.kind!="file").count(),"scope":entries.first().map(|n|&n.scope),"hubs":entries.into_iter().filter(|n|n.kind!="file").take(5).map(display).collect::<Vec<_>>()})
            }).collect())
        }
        _ => Err(invalid("unknown graph query")),
    }
}
