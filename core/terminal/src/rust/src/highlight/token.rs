use serde::{Serialize, Deserialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum TokenType {
    Command,
    Option,
    Path,
    String,
    Pipe,
    Variable,
    Comment,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Token {
    pub token_type: TokenType,
    pub value: String,
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HighlightedLine {
    pub tokens: Vec<Token>,
}
