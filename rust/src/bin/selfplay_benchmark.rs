#[path = "../board.rs"]
mod board;
#[path = "../evaluator.rs"]
mod evaluator;
#[path = "../ai_engine.rs"]
mod ai_engine;
#[path = "../mcts_ai.rs"]
mod mcts_ai;

use ai_engine::{AiConfig, GomokuAi};
use board::{Board, Color};
use mcts_ai::{MctsAi, MctsConfig, MAX_CHILDREN_EASY, MAX_CHILDREN_MEDIUM};
use std::env;
use std::time::Instant;

#[derive(Clone, Copy, Debug)]
enum Level {
    Easy,
    Medium,
    Hard,
    Master,
}

#[derive(Default)]
struct SideStats {
    wins: u32,
    losses: u32,
    draws: u32,
    total_moves: u32,
    total_ms: u128,
}

enum AiKind {
    Minimax(GomokuAi),
    Mcts(MctsAi),
}

impl AiKind {
    fn take_turn(&mut self, board: &Board) -> Option<(usize, usize)> {
        match self {
            AiKind::Minimax(ai) => ai.take_turn(board),
            AiKind::Mcts(ai) => ai.take_turn(board),
        }
    }
}

fn parse_level(s: &str) -> Level {
    match s.to_ascii_lowercase().as_str() {
        "easy" => Level::Easy,
        "medium" => Level::Medium,
        "hard" => Level::Hard,
        "master" => Level::Master,
        _ => Level::Medium,
    }
}

fn create_ai(level: Level, color: Color) -> AiKind {
    match level {
        Level::Easy => AiKind::Mcts(MctsAi::new(MctsConfig {
            player: color,
            time_limit_ms: 650,
            exploration_c: 2.2,
            max_children: MAX_CHILDREN_EASY,
        })),
        Level::Medium => AiKind::Mcts(MctsAi::new(MctsConfig {
            player: color,
            time_limit_ms: 1800,
            exploration_c: 1.1,
            max_children: MAX_CHILDREN_MEDIUM,
        })),
        Level::Hard => AiKind::Minimax(GomokuAi::new(AiConfig {
            max_depth: 12,
            time_limit_ms: 4000,
            player: color,
        })),
        Level::Master => AiKind::Minimax(GomokuAi::new(AiConfig {
            max_depth: 20,
            time_limit_ms: 12000,
            player: color,
        })),
    }
}

fn first_legal(board: &Board) -> Option<(usize, usize)> {
    board.generate_moves().into_iter().next()
}

fn apply_opening(board: &mut Board, opening_id: usize) {
    // 固定几组开局，降低先手偏差
    let openings: &[&[(usize, usize, Color)]] = &[
        &[(7, 7, Color::Black), (7, 8, Color::White)],
        &[(7, 7, Color::Black), (8, 7, Color::White)],
        &[(7, 7, Color::Black), (6, 7, Color::White)],
        &[(7, 7, Color::Black), (6, 6, Color::White)],
    ];
    let seq = openings[opening_id % openings.len()];
    for &(r, c, color) in seq {
        let _ = board.place(r, c, color);
    }
}

fn play_one_game(
    level_a: Level,
    level_b: Level,
    a_is_black: bool,
    opening_id: usize,
) -> (Option<Color>, u128, u128, u32, u32) {
    let mut board = Board::new();
    apply_opening(&mut board, opening_id);

    let (mut black_ai, mut white_ai) = if a_is_black {
        (create_ai(level_a, Color::Black), create_ai(level_b, Color::White))
    } else {
        (create_ai(level_b, Color::Black), create_ai(level_a, Color::White))
    };

    let mut current = if board.move_count % 2 == 0 { Color::Black } else { Color::White };
    let mut black_ms: u128 = 0;
    let mut white_ms: u128 = 0;
    let mut black_moves: u32 = 0;
    let mut white_moves: u32 = 0;

    while board.move_count < 225 {
        let start = Instant::now();
        let mv = match current {
            Color::Black => black_ai.take_turn(&board),
            Color::White => white_ai.take_turn(&board),
            Color::Empty => None,
        }
        .or_else(|| first_legal(&board));

        let elapsed = start.elapsed().as_millis();

        let Some((r, c)) = mv else {
            return (None, black_ms, white_ms, black_moves, white_moves);
        };

        if !board.place(r, c, current) {
            // 返回非法落子时回退到首个合法点
            if let Some((fr, fc)) = first_legal(&board) {
                let _ = board.place(fr, fc, current);
                if current == Color::Black {
                    black_ms += elapsed;
                    black_moves += 1;
                } else {
                    white_ms += elapsed;
                    white_moves += 1;
                }
                if board.check_win(fr, fc, current) {
                    return (Some(current), black_ms, white_ms, black_moves, white_moves);
                }
                current = current.opponent();
                continue;
            }
            return (None, black_ms, white_ms, black_moves, white_moves);
        }

        if current == Color::Black {
            black_ms += elapsed;
            black_moves += 1;
        } else {
            white_ms += elapsed;
            white_moves += 1;
        }

        if board.check_win(r, c, current) {
            return (Some(current), black_ms, white_ms, black_moves, white_moves);
        }

        current = current.opponent();
    }

    (None, black_ms, white_ms, black_moves, white_moves)
}

fn main() {
    let args: Vec<String> = env::args().collect();

    let level_a = args.get(1).map(|s| parse_level(s)).unwrap_or(Level::Master);
    let level_b = args.get(2).map(|s| parse_level(s)).unwrap_or(Level::Hard);
    let games: usize = args
        .get(3)
        .and_then(|s| s.parse::<usize>().ok())
        .unwrap_or(50);

    let mut a_stats = SideStats::default();
    let mut b_stats = SideStats::default();

    for g in 0..games {
        let a_is_black = g % 2 == 0;
        let opening_id = g;
        let (winner, black_ms, white_ms, black_moves, white_moves) =
            play_one_game(level_a, level_b, a_is_black, opening_id);

        let (a_color, b_color) = if a_is_black {
            (Color::Black, Color::White)
        } else {
            (Color::White, Color::Black)
        };

        // 统计耗时和步数
        if a_color == Color::Black {
            a_stats.total_ms += black_ms;
            a_stats.total_moves += black_moves;
            b_stats.total_ms += white_ms;
            b_stats.total_moves += white_moves;
        } else {
            a_stats.total_ms += white_ms;
            a_stats.total_moves += white_moves;
            b_stats.total_ms += black_ms;
            b_stats.total_moves += black_moves;
        }

        match winner {
            Some(c) if c == a_color => {
                a_stats.wins += 1;
                b_stats.losses += 1;
            }
            Some(c) if c == b_color => {
                b_stats.wins += 1;
                a_stats.losses += 1;
            }
            _ => {
                a_stats.draws += 1;
                b_stats.draws += 1;
            }
        }
    }

    let a_avg_ms = if a_stats.total_moves > 0 {
        a_stats.total_ms as f64 / a_stats.total_moves as f64
    } else {
        0.0
    };
    let b_avg_ms = if b_stats.total_moves > 0 {
        b_stats.total_ms as f64 / b_stats.total_moves as f64
    } else {
        0.0
    };

    println!("=== Gomoku Selfplay Benchmark ===");
    println!("A = {:?}, B = {:?}, games = {}", level_a, level_b, games);
    println!(
        "A: W/L/D = {}/{}/{}, avg_ms_per_move = {:.2}",
        a_stats.wins, a_stats.losses, a_stats.draws, a_avg_ms
    );
    println!(
        "B: W/L/D = {}/{}/{}, avg_ms_per_move = {:.2}",
        b_stats.wins, b_stats.losses, b_stats.draws, b_avg_ms
    );
    if games > 0 {
        println!(
            "A win rate: {:.2}%",
            (a_stats.wins as f64) * 100.0 / (games as f64)
        );
    }
    println!("Usage: cargo run --bin selfplay_benchmark -- <A> <B> <games>");
    println!("Example: cargo run --release --bin selfplay_benchmark -- master hard 100");
}
