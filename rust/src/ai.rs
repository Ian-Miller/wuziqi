use crate::board::{Board, Color, BOARD_SIZE};
use crate::evaluator::evaluate_position;

/// 搜索深度
const MAX_DEPTH: i32 = 3;

/// 极大极小值搜索 + AlphaBeta 剪枝
pub fn find_best_move(board: &Board, player: Color) -> Option<(usize, usize)> {
    let moves = board.generate_moves();

    if moves.is_empty() {
        return None;
    }

    // 如果只有一个候选，直接返回
    if moves.len() == 1 {
        return Some(moves[0]);
    }

    let mut best_score = i32::MIN;
    let mut best_move = None;

    for (row, col) in moves {
        // 尝试落子
        let mut new_board = board.clone();
        if !new_board.place(row, col, player) {
            continue;
        }

        // 检查是否直接获胜
        if new_board.check_win(row, col, player) {
            return Some((row, col)); // 找到必胜走法
        }

        // 递归搜索
        let score = -minimax(
            &new_board,
            player.opponent(),
            MAX_DEPTH - 1,
            i32::MIN + 1,
            i32::MAX - 1,
        );

        if score > best_score {
            best_score = score;
            best_move = Some((row, col));
        }
    }

    best_move
}

/// Minimax + AlphaBeta 剪枝
fn minimax(board: &Board, player: Color, depth: i32, alpha: i32, beta: i32) -> i32 {
    // 终局判断：没有可行走法
    let moves = board.generate_moves();
    if moves.is_empty() {
        return 0;
    }

    // 达到搜索深度，使用评估函数
    if depth <= 0 {
        return evaluate_board(board, player);
    }

    let mut best_score = i32::MIN;
    let mut alpha = alpha;

    for (row, col) in moves {
        let mut new_board = board.clone();
        if !new_board.place(row, col, player) {
            continue;
        }

        // 检查是否获胜
        if new_board.check_win(row, col, player) {
            return 1_000_000 + depth; // 胜利加分，越早越好
        }

        // 递归搜索对手
        let score = -minimax(&new_board, player.opponent(), depth - 1, -beta, -alpha);

        if score > best_score {
            best_score = score;
        }

        // AlphaBeta 剪枝
        if score > alpha {
            alpha = score;
        }
        if alpha >= beta {
            break; // 剪枝
        }
    }

    best_score
}

/// 快速评估：只检查是否有立即获胜或必须防守的走法
pub fn quick_evaluate(board: &Board, player: Color) -> Option<(usize, usize)> {
    let moves = board.generate_moves();

    // 1. 检查是否能立即获胜
    for (row, col) in &moves {
        let mut test_board = board.clone();
        if test_board.place(*row, *col, player) && test_board.check_win(*row, *col, player) {
            return Some((*row, *col));
        }
    }

    // 2. 检查是否需要阻止对手获胜
    let opponent = player.opponent();
    for (row, col) in &moves {
        let mut test_board = board.clone();
        if test_board.place(*row, *col, opponent) && test_board.check_win(*row, *col, opponent) {
            return Some((*row, *col)); // 必须防守
        }
    }

    None // 没有紧急情况
}

/// 评估棋盘（简化版，用于 ai.rs 的独立 minimax）
fn evaluate_board(board: &Board, player: Color) -> i32 {
    let mut score = 0;

    // 评估所有空位的价值
    for r in 0..BOARD_SIZE {
        for c in 0..BOARD_SIZE {
            if board.get(r, c) == Color::Empty {
                // 评估玩家在此落子的价值
                score += evaluate_position(board, r, c, player);
                // 减去对手在此落子的价值（防守）
                score -= evaluate_position(board, r, c, player.opponent()) * 9 / 10;
            }
        }
    }

    score
}
