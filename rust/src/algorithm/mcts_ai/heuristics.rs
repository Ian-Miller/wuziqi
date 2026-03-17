use crate::board::{Board, Color, BOARD_SIZE};
use crate::evaluator::{analyze_line, evaluate_position, match_pattern_score, SCORE_BLOCKED_FOUR, SCORE_FOUR};

use super::{THREAT_BLOCK_WIN, THREAT_DOUBLE_FOUR, THREAT_DOUBLE_THREE, THREAT_FOUR, THREAT_THREE, THREAT_THREE_FOUR, THREAT_WIN};

pub(super) fn is_diagonal_threat(board: &Board, row: usize, col: usize, color: Color) -> bool {
    let diag_dirs = [(1i32, 1i32), (1i32, -1i32)];
    for &(dr, dc) in &diag_dirs {
        let mut count = 1i32;
        let (mut r, mut c) = (row as i32 + dr, col as i32 + dc);
        while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == color
        {
            count += 1;
            r += dr;
            c += dc;
        }
        let (mut r, mut c) = (row as i32 - dr, col as i32 - dc);
        while r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
            && board.get(r as usize, c as usize) == color
        {
            count += 1;
            r -= dr;
            c -= dc;
        }
        if count >= 4 {
            return true;
        }
    }
    false
}

pub(super) fn top_k_moves(board: &Board, mover: Color, k: usize) -> Vec<(usize, usize)> {
    let raw = board.generate_moves();
    let opp = mover.opponent();
    let mut scored: Vec<((usize, usize), i32)> = raw
        .into_iter()
        .map(|(r, c)| {
            let base = evaluate_position(board, r, c, mover)
                + evaluate_position(board, r, c, opp) * 9 / 10;
            let threat = threat_score(board, r, c, mover, opp);
            let proximity = proximity_bonus(board, r, c);
            ((r, c), base + threat + proximity)
        })
        .collect();
    scored.sort_unstable_by(|a, b| b.1.cmp(&a.1));
    scored.into_iter().take(k).map(|(pos, _)| pos).collect()
}

fn proximity_bonus(board: &Board, row: usize, col: usize) -> i32 {
    let r0 = row as i32;
    let c0 = col as i32;
    for dr in -1i32..=1 {
        for dc in -1i32..=1 {
            if dr == 0 && dc == 0 {
                continue;
            }
            let nr = r0 + dr;
            let nc = c0 + dc;
            if nr >= 0 && nr < BOARD_SIZE as i32 && nc >= 0 && nc < BOARD_SIZE as i32 {
                if board.get(nr as usize, nc as usize) != Color::Empty {
                    return 300;
                }
            }
        }
    }
    0
}

fn threat_score(board: &Board, row: usize, col: usize, mover: Color, opp: Color) -> i32 {
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];
    let mut my_fours = 0i32;
    let mut my_bfours = 0i32;
    let mut my_threes = 0i32;
    let mut opp_fours = 0i32;
    let mut opp_bfours = 0i32;
    let mut opp_threes = 0i32;

    for &(dr, dc) in &dirs {
        let my_pat = analyze_line(board, row, col, dr, dc, mover);
        let (cnt_m, emp_m, lb_m, rb_m) = my_pat;
        let ms = match_pattern_score(my_pat);
        if ms >= SCORE_FOUR {
            my_fours += 1;
        } else if ms >= SCORE_BLOCKED_FOUR {
            let is_active_three = cnt_m == 2 && emp_m >= 4 && !lb_m && !rb_m;
            if is_active_three {
                my_threes += 1;
            } else {
                my_bfours += 1;
            }
        }

        let opp_pat = analyze_line(board, row, col, dr, dc, opp);
        let (cnt_o, emp_o, lb_o, rb_o) = opp_pat;
        let os = match_pattern_score(opp_pat);
        if os >= SCORE_FOUR {
            opp_fours += 1;
        } else if os >= SCORE_BLOCKED_FOUR {
            let is_active_three = cnt_o == 2 && emp_o >= 4 && !lb_o && !rb_o;
            if is_active_three {
                opp_threes += 1;
            } else {
                opp_bfours += 1;
            }
        }
    }

    let mut bonus = 0i32;
    if my_fours >= 1 {
        bonus += THREAT_WIN;
    } else if my_bfours >= 2 {
        bonus += THREAT_DOUBLE_FOUR;
    } else if my_bfours >= 1 && my_threes >= 1 {
        bonus += THREAT_THREE_FOUR;
    } else if my_threes >= 2 {
        bonus += THREAT_DOUBLE_THREE;
    } else if my_bfours >= 1 {
        bonus += THREAT_FOUR;
    } else if my_threes >= 1 {
        bonus += THREAT_THREE;
    }

    if opp_fours >= 1 {
        bonus += THREAT_BLOCK_WIN;
    } else if opp_bfours >= 2 {
        bonus += THREAT_DOUBLE_FOUR / 2;
    } else if opp_bfours >= 1 && opp_threes >= 1 {
        bonus += THREAT_THREE_FOUR / 2;
    } else if opp_threes >= 2 {
        bonus += THREAT_DOUBLE_THREE / 2;
    } else if opp_threes >= 1 {
        bonus += THREAT_THREE;
    }

    bonus
}
