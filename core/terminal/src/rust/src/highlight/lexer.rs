use super::token::{Token, TokenType, HighlightedLine};

pub struct Lexer;

impl Lexer {
    pub fn new() -> Self {
        Self
    }

    pub fn tokenize(&self, input: &str) -> Vec<Token> {
        let mut tokens = Vec::new();
        let mut chars = input.char_indices().peekable();
        let mut current = String::new();
        let mut start = 0;
        let mut in_string = false;
        let mut string_char = ' ';

        while let Some((i, c)) = chars.next() {
            if in_string {
                current.push(c);
                if c == string_char {
                    tokens.push(Token {
                        token_type: TokenType::String,
                        value: current.clone(),
                        start,
                        end: i + 1,
                    });
                    current.clear();
                    in_string = false;
                }
                continue;
            }

            match c {
                ' ' | '\t' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    start = i + 1;
                }
                '|' | '>' | '<' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    tokens.push(Token {
                        token_type: TokenType::Pipe,
                        value: c.to_string(),
                        start: i,
                        end: i + 1,
                    });
                    start = i + 1;
                }
                '"' | '\'' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    in_string = true;
                    string_char = c;
                    start = i;
                    current.push(c);
                }
                '$' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    start = i;
                    current.push(c);
                    while let Some((_, next)) = chars.peek() {
                        if next.is_alphanumeric() || *next == '_' || *next == '(' || *next == ')' {
                            current.push(*next);
                            chars.next();
                        } else {
                            break;
                        }
                    }
                    tokens.push(Token {
                        token_type: TokenType::Variable,
                        value: current.clone(),
                        start,
                        end: start + current.len(),
                    });
                    current.clear();
                    start = i + current.len();
                }
                '#' => {
                    if !current.is_empty() {
                        tokens.push(self.classify_token(&current, start, i));
                        current.clear();
                    }
                    let comment: String = input[i..].chars().collect();
                    tokens.push(Token {
                        token_type: TokenType::Comment,
                        value: comment,
                        start: i,
                        end: input.len(),
                    });
                    break;
                }
                _ => {
                    if current.is_empty() {
                        start = i;
                    }
                    current.push(c);
                }
            }
        }

        if !current.is_empty() {
            tokens.push(self.classify_token(&current, start, input.len()));
        }

        tokens
    }

    fn classify_token(&self, s: &str, start: usize, end: usize) -> Token {
        let token_type = if s.starts_with('-') {
            TokenType::Option
        } else if s.starts_with('/') || s.starts_with("./") || s.starts_with("..") {
            TokenType::Path
        } else {
            TokenType::Command
        };

        Token {
            token_type,
            value: s.to_string(),
            start,
            end,
        }
    }

    pub fn highlight_line(&self, input: &str) -> HighlightedLine {
        HighlightedLine {
            tokens: self.tokenize(input),
        }
    }
}

impl Default for Lexer {
    fn default() -> Self {
        Self::new()
    }
}
