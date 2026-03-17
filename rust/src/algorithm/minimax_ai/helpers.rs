use super::*;

pub(super) fn when_threat_penalty(opp_best_threat: i32, move_count: usize) -> i32 {
    if opp_best_threat >= SCORE_FOUR {
        10_000 + opp_best_threat / 2
    } else if opp_best_threat >= DOUBLE_THREAT_THRESHOLD {
        let base = if move_count <= 24 { 6_000 } else { 4_000 };
        base + opp_best_threat / 2
    } else if opp_best_threat >= SCORE_BLOCKED_FOUR {
        1_200 + opp_best_threat / 3
    } else if opp_best_threat >= SCORE_THREE {
        if move_count <= 14 {
            opp_best_threat / 2
        } else {
            opp_best_threat / 3
        }
    } else {
        0
    }
}

/// 枚举当前局面中 `player` 的所有"成五威胁格"：
/// 即落子后立即形成五连的空格。VCF 搜索用来判断对手是否需要封堵。
pub(super) fn vcf_win_threats(board: &Board, player: Color) -> Vec<(usize, usize)> {
    board
        .generate_moves()
        .into_iter()
        .filter(|&(r, c)| {
            let mut b = board.clone();
            b.place(r, c, player) && b.check_win(r, c, player)
        })
        .collect()
}

/// 判断若 `player` 在 `(row, col)` 落子，落子后是否构成"冲四"（四连，至少一端开放）。
/// 与 `is_real_four_threat` 不同之处在于：此函数先临时落子，再在已落子的棋盘上检测。
pub(super) fn is_real_four_threat_if_placed(
    board: &Board,
    row: usize,
    col: usize,
    color: Color,
) -> bool {
    if board.get(row, col) != Color::Empty {
        return false;
    }
    let mut b = board.clone();
    if !b.place(row, col, color) {
        return false;
    }
    if b.check_win(row, col, color) {
        return false;
    }
    is_real_four_threat(&b, row, col, color)
}
