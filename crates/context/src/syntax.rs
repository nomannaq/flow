//! Bundled parsers produce syntax facts; cross-file resolution is a separate pass.
use super::*;
use std::sync::{Arc, Mutex, OnceLock};
use streaming_iterator::StreamingIterator;
use tree_sitter::{Language, Node};

fn tags(grammar: &Grammar) -> Option<Arc<tree_sitter::Query>> {
    type Queries = BTreeMap<String, Option<Arc<tree_sitter::Query>>>;
    static QUERIES: OnceLock<Mutex<Queries>> = OnceLock::new();
    let key = format!("{}:{}", grammar.name, grammar.language.node_kind_count());
    let cache = QUERIES.get_or_init(Default::default);
    if let Some(query) = cache.lock().unwrap().get(&key) {
        return query.clone();
    }
    let query = tree_sitter::Query::new(&grammar.language, grammar.tags)
        .ok()
        .map(Arc::new);
    cache.lock().unwrap().insert(key, query.clone());
    query
}

pub(super) struct Grammar {
    pub name: &'static str,
    pub language: Language,
    tags: &'static str,
}

pub(super) fn grammar(path: &str) -> Option<Grammar> {
    let extension = Path::new(path).extension()?.to_str()?.to_ascii_lowercase();
    let (name, language, tags) = match extension.as_str() {
        "rs" => (
            "Rust",
            tree_sitter_rust::LANGUAGE.into(),
            tree_sitter_rust::TAGS_QUERY,
        ),
        "ts" | "mts" | "cts" => (
            "TypeScript",
            tree_sitter_typescript::LANGUAGE_TYPESCRIPT.into(),
            tree_sitter_typescript::TAGS_QUERY,
        ),
        "tsx" => (
            "TSX",
            tree_sitter_typescript::LANGUAGE_TSX.into(),
            tree_sitter_typescript::TAGS_QUERY,
        ),
        "vue" => (
            "Vue",
            tree_sitter_typescript::LANGUAGE_TSX.into(),
            tree_sitter_typescript::TAGS_QUERY,
        ),
        "js" | "mjs" | "cjs" | "jsx" => (
            "JavaScript",
            tree_sitter_javascript::LANGUAGE.into(),
            tree_sitter_javascript::TAGS_QUERY,
        ),
        "py" | "pyi" => (
            "Python",
            tree_sitter_python::LANGUAGE.into(),
            tree_sitter_python::TAGS_QUERY,
        ),
        "go" => (
            "Go",
            tree_sitter_go::LANGUAGE.into(),
            tree_sitter_go::TAGS_QUERY,
        ),
        "java" => (
            "Java",
            tree_sitter_java::LANGUAGE.into(),
            tree_sitter_java::TAGS_QUERY,
        ),
        "kt" | "kts" => ("Kotlin", tree_sitter_kotlin_ng::LANGUAGE.into(), ""),
        "swift" => (
            "Swift",
            tree_sitter_swift::LANGUAGE.into(),
            tree_sitter_swift::TAGS_QUERY,
        ),
        "php" => (
            "PHP",
            tree_sitter_php::LANGUAGE_PHP.into(),
            tree_sitter_php::TAGS_QUERY,
        ),
        "r" => (
            "R",
            tree_sitter_r::LANGUAGE.into(),
            tree_sitter_r::TAGS_QUERY,
        ),
        "c" | "h" => (
            "C",
            tree_sitter_c::LANGUAGE.into(),
            tree_sitter_c::TAGS_QUERY,
        ),
        "cpp" | "cc" | "cxx" | "hpp" | "hh" | "hxx" => (
            "C++",
            tree_sitter_cpp::LANGUAGE.into(),
            tree_sitter_cpp::TAGS_QUERY,
        ),
        "rb" | "rake" => (
            "Ruby",
            tree_sitter_ruby::LANGUAGE.into(),
            tree_sitter_ruby::TAGS_QUERY,
        ),
        "cs" => (
            "C#",
            tree_sitter_c_sharp::LANGUAGE.into(),
            tree_sitter_c_sharp::TAGS_QUERY,
        ),
        "scala" | "sc" => ("Scala", tree_sitter_scala::LANGUAGE.into(), ""),
        "ex" | "exs" => (
            "Elixir",
            tree_sitter_elixir::LANGUAGE.into(),
            tree_sitter_elixir::TAGS_QUERY,
        ),
        "sol" => (
            "Solidity",
            tree_sitter_solidity::LANGUAGE.into(),
            tree_sitter_solidity::TAGS_QUERY,
        ),
        "ml" => (
            "OCaml",
            tree_sitter_ocaml::LANGUAGE_OCAML.into(),
            tree_sitter_ocaml::TAGS_QUERY,
        ),
        "mli" => (
            "OCaml",
            tree_sitter_ocaml::LANGUAGE_OCAML_INTERFACE.into(),
            tree_sitter_ocaml::TAGS_QUERY,
        ),
        "zig" => ("Zig", tree_sitter_zig::LANGUAGE.into(), ""),
        "dart" => (
            "Dart",
            tree_sitter_dart::LANGUAGE.into(),
            tree_sitter_dart::TAGS_QUERY,
        ),
        "clj" | "cljs" | "cljc" | "bb" => ("Clojure", tree_sitter_clojure::LANGUAGE.into(), ""),
        "nix" => ("Nix", tree_sitter_nix::LANGUAGE.into(), ""),
        "lua" => (
            "Lua",
            tree_sitter_lua::LANGUAGE.into(),
            tree_sitter_lua::TAGS_QUERY,
        ),
        "gleam" => (
            "Gleam",
            tree_sitter_gleam::LANGUAGE.into(),
            tree_sitter_gleam::TAGS_QUERY,
        ),
        _ => return None,
    };
    Some(Grammar {
        name,
        language,
        tags,
    })
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Reference {
    pub name: String,
    pub qualifier: Option<String>,
    pub relation: String,
    pub start_byte: usize,
    pub line: usize,
    pub arity: Option<usize>,
    pub alias: Option<String>,
    pub module: Option<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct Binding {
    pub name: String,
    pub type_name: String,
    pub start_byte: usize,
    pub scope_start: usize,
    pub scope_end: usize,
}

fn text<'a>(node: Node<'_>, source: &'a str) -> &'a str {
    &source[node.byte_range()]
}

fn identifier(node: Node<'_>) -> Option<Node<'_>> {
    if matches!(
        node.kind(),
        "identifier"
            | "property_identifier"
            | "type_identifier"
            | "field_identifier"
            | "simple_identifier"
            | "name"
            | "constant"
            | "sym_lit"
            | "value_name"
            | "module_name"
            | "type_constructor"
    ) {
        return Some(node);
    }
    for field in ["name", "declarator", "field", "attribute", "type"] {
        if let Some(found) = node.child_by_field_name(field).and_then(identifier) {
            return Some(found);
        }
    }
    let mut cursor = node.walk();
    node.named_children(&mut cursor).find_map(identifier)
}

fn declaration<'tree>(node: Node<'tree>, source: &str) -> Option<Node<'tree>> {
    let kind = node.kind();
    if matches!(
        kind,
        "variable_declarator" | "assignment" | "binary_operator" | "binding"
    ) {
        let value = node
            .child_by_field_name("value")
            .or_else(|| node.child_by_field_name("right"))
            .or_else(|| node.child_by_field_name("expression"))?;
        if !matches!(
            value.kind(),
            "arrow_function"
                | "function_expression"
                | "function_definition"
                | "lambda"
                | "lambda_expression"
        ) {
            return None;
        }
        return node
            .child_by_field_name("name")
            .or_else(|| node.child_by_field_name("left"))
            .or_else(|| node.child_by_field_name("attrpath"))
            .and_then(identifier);
    }
    if kind == "list_lit" {
        let head = node.named_child(0)?;
        if matches!(
            text(head, source),
            "defn"
                | "defn-"
                | "defmacro"
                | "defmulti"
                | "defmethod"
                | "def"
                | "ns"
                | "defrecord"
                | "deftype"
                | "defprotocol"
        ) {
            return node.named_child(1);
        }
    }
    if kind == "call"
        && matches!(
            node.named_child(0).map(|n| text(n, source)),
            Some("def" | "defp" | "defmodule" | "defmacro" | "defprotocol" | "defimpl")
        )
    {
        return node.named_child(1).and_then(identifier);
    }
    if matches!(
        kind,
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
            | "function_declaration"
            | "generator_function_declaration"
            | "method_definition"
            | "function_signature"
            | "method_signature"
            | "class_declaration"
            | "abstract_class_declaration"
            | "interface_declaration"
            | "type_alias_declaration"
            | "enum_declaration"
            | "function_definition"
            | "class_definition"
            | "method_declaration"
            | "constructor_declaration"
            | "annotation_type_declaration"
            | "type_spec"
            | "const_spec"
            | "var_spec"
            | "object_declaration"
            | "property_declaration"
            | "type_alias"
            | "protocol_declaration"
            | "struct_declaration"
            | "extension_declaration"
            | "typealias_declaration"
            | "struct_specifier"
            | "class_specifier"
            | "enum_specifier"
            | "type_definition"
            | "method"
            | "singleton_method"
            | "class"
            | "module"
            | "record_declaration"
            | "delegate_declaration"
            | "trait_definition"
            | "object_definition"
            | "val_definition"
            | "contract_declaration"
            | "event_definition"
            | "error_declaration"
            | "module_definition"
            | "let_binding"
            | "variable_declaration"
            | "constant"
            | "external_function"
            | "function"
    ) {
        return node
            .child_by_field_name("name")
            .or_else(|| node.child_by_field_name("declarator"))
            .or_else(|| node.child_by_field_name("type"))
            .and_then(identifier)
            .or_else(|| {
                if matches!(
                    kind,
                    "function_declaration"
                        | "class_declaration"
                        | "object_declaration"
                        | "let_binding"
                        | "module_definition"
                ) {
                    identifier(node)
                } else {
                    None
                }
            });
    }
    None
}

fn add_symbol(
    out: &mut BTreeMap<usize, Symbol>,
    node: Node<'_>,
    name: Node<'_>,
    source: &str,
    language: &str,
) {
    let name = text(name, source).trim();
    if name.is_empty() || name.len() > 256 {
        return;
    }
    let end = node
        .child_by_field_name("body")
        .map_or(node.end_byte(), |n| n.start_byte());
    let parameters = node
        .child_by_field_name("parameters")
        .or_else(|| node.child_by_field_name("parameter_list"))
        .or_else(|| {
            node.child_by_field_name("declarator")
                .and_then(|n| n.child_by_field_name("parameters"))
        })
        .or_else(|| {
            let mut cursor = node.walk();
            node.named_children(&mut cursor).find(|n| {
                matches!(
                    n.kind(),
                    "parameters"
                        | "function_value_parameters"
                        | "formal_parameters"
                        | "parameter_list"
                )
            })
        });
    out.entry(node.start_byte()).or_insert_with(|| Symbol {
        name: name.into(),
        kind: node.kind().into(),
        signature: source[node.start_byte()..end].chars().take(512).collect(),
        start_line: node.start_position().row + 1,
        end_line: node.end_position().row + 1,
        start_byte: node.start_byte(),
        end_byte: node.end_byte(),
        parent: None,
        confidence: if node.has_error() {
            "partialSyntax"
        } else {
            "syntaxOnly"
        }
        .into(),
        arity: parameters
            .filter(|n| !text(*n, source).contains(['=', '*', '.']))
            .map(|n| {
                let mut c = n.walk();
                n.named_children(&mut c)
                    .filter(|n| {
                        !matches!(n.kind(), "comment" | "self_parameter")
                            && !matches!(text(*n, source).trim(), "self" | "cls" | "void")
                    })
                    .count()
            }),
        exported: {
            let signature = source[node.start_byte()..end].trim_start();
            match language {
                "Rust" | "Gleam" => {
                    Some(signature.starts_with("pub ") || signature.starts_with("pub("))
                }
                "TypeScript" | "TSX" | "JavaScript" | "Vue" => Some(
                    signature.starts_with("export ")
                        || node
                            .parent()
                            .is_some_and(|n| n.kind() == "export_statement"),
                ),
                "Go" => Some(name.chars().next().is_some_and(char::is_uppercase)),
                "Java" | "C#" => {
                    if signature.contains("public ") {
                        Some(true)
                    } else if signature.contains("private ") {
                        Some(false)
                    } else {
                        None
                    }
                }
                _ => None,
            }
        },
    });
}

fn reference(node: Node<'_>, target: Node<'_>, source: &str, relation: &str) -> Option<Reference> {
    let target = text(target, source).trim();
    if target.len() > 512 || target.is_empty() {
        return None;
    }
    let target = target.trim_end_matches('!');
    let split = target
        .rfind("::")
        .map(|i| (i, 2))
        .or_else(|| target.rfind('.').map(|i| (i, 1)))
        .or_else(|| target.rfind("->").map(|i| (i, 2)));
    let (qualifier, name) = split.map_or((None, target), |(i, width)| {
        (Some(target[..i].to_owned()), &target[i + width..])
    });
    if !name
        .chars()
        .all(|c| c.is_alphanumeric() || c == '_' || c == '$')
    {
        return None;
    }
    let arguments = node.child_by_field_name("arguments");
    Some(Reference {
        name: name.into(),
        qualifier,
        relation: relation.into(),
        start_byte: node.start_byte(),
        line: node.start_position().row + 1,
        arity: arguments.map(|n| n.named_child_count()),
        alias: None,
        module: None,
    })
}

/// Static import syntax only: relative modules are resolved against indexed files.
fn imports(node: Node<'_>, source: &str) -> Vec<Reference> {
    let raw = text(node, source);
    if raw.len() > 4096 {
        return Vec::new();
    }
    if node.kind() == "use_declaration" {
        let raw = raw
            .trim()
            .trim_start_matches("pub ")
            .trim_start_matches("use ")
            .trim_end_matches(';');
        let (module, names) = if let Some((prefix, names)) = raw.split_once('{') {
            (prefix.trim_end_matches("::"), names.trim_end_matches('}'))
        } else {
            raw.rsplit_once("::").unwrap_or((raw, raw))
        };
        return names
            .split(',')
            .take(64)
            .filter_map(|name| {
                let mut parts = name.split_whitespace();
                let name = parts.next()?;
                if name == "*" {
                    return None;
                }
                let alias = if parts.next() == Some("as") {
                    parts.next().map(str::to_owned)
                } else {
                    None
                };
                Some(Reference {
                    name: name.into(),
                    alias,
                    module: Some(module.into()),
                    qualifier: None,
                    relation: "imports".into(),
                    start_byte: node.start_byte(),
                    line: node.start_position().row + 1,
                    arity: None,
                })
            })
            .collect();
    }
    let mut words: Vec<_> = raw
        .split(|c: char| c.is_whitespace() || matches!(c, ';' | ',' | '{' | '}' | '(' | ')'))
        .filter(|s| !s.is_empty())
        .collect();
    let source_field = node
        .child_by_field_name("source")
        .or_else(|| node.child_by_field_name("module_name"));
    let quoted = raw
        .split(['\'', '"'])
        .nth(1)
        .filter(|_| raw.contains(['\'', '"']));
    let module = source_field
        .map(|n| text(n, source))
        .or(quoted)
        .unwrap_or_else(|| words.get(1).copied().unwrap_or(""));
    let module = module.trim_matches(['\'', '"', ';']);
    if module.is_empty() {
        return Vec::new();
    }
    if let Some((_, tail)) = raw.split_once("* as ")
        && let Some(alias) = tail.split_whitespace().next()
    {
        return vec![Reference {
            name: "*".into(),
            qualifier: None,
            relation: "imports".into(),
            start_byte: node.start_byte(),
            line: node.start_position().row + 1,
            arity: None,
            alias: Some(alias.into()),
            module: Some(module.into()),
        }];
    }
    if words.first() == Some(&"from") {
        if let Some(i) = words.iter().position(|s| *s == "import") {
            words = words[i + 1..].to_vec();
        }
    } else {
        words.retain(|w| {
            !matches!(
                *w,
                "import" | "export" | "from" | "use" | "pub" | "static" | "type"
            ) && w.trim_matches(['\'', '"']) != module
        });
    }
    let names: Vec<_> = words
        .iter()
        .enumerate()
        .filter_map(|(i, word)| {
            if matches!(*word, "as" | "*")
                || i > 0 && words[i - 1] == "as"
                || word.contains(['\'', '"', '/'])
            {
                return None;
            }
            let alias = if words.get(i + 1) == Some(&"as") {
                words.get(i + 2).map(|s| (*s).to_owned())
            } else {
                None
            };
            let name = word
                .rsplit([':', '.'])
                .find(|s| !s.is_empty())
                .unwrap_or(word);
            Some(Reference {
                name: name.into(),
                qualifier: None,
                relation: "imports".into(),
                start_byte: node.start_byte(),
                line: node.start_position().row + 1,
                arity: None,
                alias,
                module: Some(module.into()),
            })
        })
        .take(64)
        .collect();
    if names.is_empty() {
        vec![Reference {
            name: module.into(),
            qualifier: None,
            relation: "imports".into(),
            start_byte: node.start_byte(),
            line: node.start_position().row + 1,
            arity: None,
            alias: None,
            module: Some(module.into()),
        }]
    } else {
        names
    }
}

pub(super) fn extract(
    path: &str,
    source: &str,
    cancel: &CancellationToken,
    started: Instant,
) -> Result<(Vec<Symbol>, Vec<Reference>, Vec<Binding>)> {
    let grammar = grammar(path).ok_or_else(|| invalid("unsupported context language"))?;
    let mut parser = tree_sitter::Parser::new();
    parser
        .set_language(&grammar.language)
        .map_err(|_| invalid("context grammar mismatch"))?;
    let mut progress = |_: &tree_sitter::ParseState| {
        if check(cancel, started).is_err() {
            std::ops::ControlFlow::Break(())
        } else {
            std::ops::ControlFlow::Continue(())
        }
    };
    // Mask templates/styles without shifting UTF-8 byte or line offsets. Script
    // syntax maps directly back to the original Vue file, including script setup.
    let masked = if grammar.name == "Vue" {
        static SCRIPTS: OnceLock<regex::Regex> = OnceLock::new();
        let pattern = SCRIPTS
            .get_or_init(|| regex::Regex::new(r"(?is)<script\b[^>]*>(.*?)</script\s*>").unwrap());
        let mut bytes: Vec<_> = source
            .bytes()
            .map(|b| if b == b'\n' || b == b'\r' { b } else { b' ' })
            .collect();
        for capture in pattern.captures_iter(source) {
            if let Some(script) = capture.get(1) {
                bytes[script.range()].copy_from_slice(script.as_str().as_bytes());
            }
        }
        Some(bytes)
    } else {
        None
    };
    let input = masked.as_deref().unwrap_or(source.as_bytes());
    let tree = parser
        .parse_with_options(
            &mut |byte, _| &input[byte..],
            None,
            Some(tree_sitter::ParseOptions::new().progress_callback(&mut progress)),
        )
        .ok_or_else(|| invalid("context parsing cancelled"))?;
    let mut symbols = BTreeMap::new();
    let mut refs = Vec::new();
    // Standard upstream tags provide breadth; the node walker fills language-specific gaps.
    if let Some(query) = tags(&grammar) {
        let mut cursor = tree_sitter::QueryCursor::new();
        let mut matches = cursor.matches(&query, tree.root_node(), input);
        while let Some(found) = matches.next() {
            check(cancel, started)?;
            let name = found
                .captures
                .iter()
                .find(|c| query.capture_names()[c.index as usize] == "name")
                .map(|c| c.node);
            for capture in found.captures {
                let role = query.capture_names()[capture.index as usize];
                if role.starts_with("definition.")
                    && let Some(name) = name
                {
                    add_symbol(&mut symbols, capture.node, name, source, grammar.name);
                }
                if role.starts_with("reference.")
                    && let Some(target) = name
                    && let Some(r) = reference(
                        capture.node,
                        target,
                        source,
                        if role.ends_with("call") {
                            "calls"
                        } else {
                            "references"
                        },
                    )
                {
                    refs.push(r);
                }
            }
            if symbols.len() >= 4096 || refs.len() >= 8192 {
                return Err(invalid("too many syntax facts in file"));
            }
        }
    }
    let mut stack = vec![tree.root_node()];
    let mut nodes = Vec::new();
    while let Some(node) = stack.pop() {
        check(cancel, started)?;
        if symbols.len() >= 4096 || refs.len() >= 8192 {
            return Err(invalid("too many syntax facts in file"));
        }
        if let Some(name) = declaration(node, source) {
            add_symbol(&mut symbols, node, name, source, grammar.name);
        }
        if matches!(
            node.kind(),
            "call_expression"
                | "invocation_expression"
                | "call"
                | "method_invocation"
                | "macro_invocation"
                | "object_creation_expression"
                | "new_expression"
                | "function_call_expression"
                | "method_call"
                | "list_lit"
                | "function_call"
                | "application_expression"
                | "apply_expression"
        ) {
            let target = node
                .child_by_field_name("function")
                .or_else(|| node.child_by_field_name("name"))
                .or_else(|| node.child_by_field_name("macro"))
                .or_else(|| node.child_by_field_name("type"))
                .or_else(|| node.child_by_field_name("target"))
                .or_else(|| node.named_child(0));
            if let Some(target) = target
                && let Some(mut r) = reference(node, target, source, "calls")
            {
                if let Some(object) = node.child_by_field_name("object") {
                    r.qualifier = Some(text(object, source).into());
                }
                refs.push(r);
            }
        }
        if matches!(
            node.kind(),
            "import_statement"
                | "import_from_statement"
                | "import_declaration"
                | "use_declaration"
                | "import_header"
                | "import_directive"
        ) {
            refs.extend(imports(node, source));
        }
        for (field, relation) in [
            ("superclass", "extends"),
            ("super_classes", "extends"),
            ("interfaces", "implements"),
            ("trait", "implements"),
        ] {
            if let Some(target) = node.child_by_field_name(field).and_then(identifier)
                && let Some(r) = reference(node, target, source, relation)
            {
                refs.push(r);
            }
        }
        if matches!(
            node.kind(),
            "extends_clause"
                | "implements_clause"
                | "superclass"
                | "super_interfaces"
                | "base_list"
        ) {
            let mut cursor = node.walk();
            for child in node.named_children(&mut cursor) {
                if let Some(target) = identifier(child)
                    && let Some(r) = reference(
                        child,
                        target,
                        source,
                        if matches!(node.kind(), "implements_clause" | "super_interfaces") {
                            "implements"
                        } else {
                            "extends"
                        },
                    )
                {
                    refs.push(r);
                }
            }
        }
        if matches!(node.kind(), "type_identifier" | "type_name")
            && let Some(r) = reference(node, node, source, "references")
        {
            refs.push(r);
        }
        nodes.push(node);
        let mut cursor = node.walk();
        for child in node.named_children(&mut cursor) {
            stack.push(child);
        }
    }
    let mut symbols: Vec<_> = symbols.into_values().collect();
    let mut enclosing: Vec<usize> = Vec::new();
    for i in 0..symbols.len() {
        while enclosing
            .last()
            .is_some_and(|p| symbols[*p].end_byte <= symbols[i].start_byte)
        {
            enclosing.pop();
        }
        symbols[i].parent = enclosing
            .last()
            .map(|p| format!("{}:{}", symbols[*p].name, symbols[*p].start_line));
        enclosing.push(i);
    }
    let mut bindings = Vec::new();
    for node in nodes {
        let name = node
            .child_by_field_name("name")
            .or_else(|| node.child_by_field_name("pattern"))
            .or_else(|| node.child_by_field_name("left"));
        let declared = node.child_by_field_name("type");
        let value = node
            .child_by_field_name("value")
            .or_else(|| node.child_by_field_name("right"));
        let inferred = value
            .filter(|n| {
                matches!(
                    n.kind(),
                    "new_expression" | "object_creation_expression" | "call" | "call_expression"
                )
            })
            .and_then(|n| {
                n.child_by_field_name("constructor")
                    .or_else(|| n.child_by_field_name("type"))
                    .or_else(|| n.child_by_field_name("function"))
            });
        if let (Some(name), Some(typ)) = (
            name.and_then(identifier),
            declared.or(inferred).and_then(identifier),
        ) {
            let typ = text(typ, source);
            if typ.chars().next().is_some_and(char::is_uppercase) {
                let scope = symbols
                    .iter()
                    .filter(|s| {
                        s.start_byte <= node.start_byte()
                            && s.end_byte >= node.end_byte()
                            && s.start_byte != node.start_byte()
                    })
                    .min_by_key(|s| s.end_byte - s.start_byte);
                bindings.push(Binding {
                    name: text(name, source).into(),
                    type_name: typ.into(),
                    start_byte: node.start_byte(),
                    scope_start: scope.map_or(0, |s| s.start_byte),
                    scope_end: scope.map_or(source.len(), |s| s.end_byte),
                });
            }
        }
    }
    refs.sort_by(|a, b| {
        (
            a.start_byte,
            &a.relation,
            &a.name,
            a.qualifier.is_none(),
            a.arity.is_none(),
        )
            .cmp(&(
                b.start_byte,
                &b.relation,
                &b.name,
                b.qualifier.is_none(),
                b.arity.is_none(),
            ))
    });
    refs.dedup_by(|a, b| {
        a.start_byte == b.start_byte && a.name == b.name && a.relation == b.relation
    });
    Ok((symbols, refs, bindings))
}
