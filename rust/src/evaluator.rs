use crate::board::{Board, Color, BOARD_SIZE};

/// 棋型分数
pub const SCORE_FIVE: i32 = 100_000; // 五连（必胜）
pub const SCORE_FOUR: i32 = 10_000; // 活四
pub const SCORE_BLOCKED_FOUR: i32 = 3_000; // 冲四（强制对手响应，比活三高一个量级）
pub const SCORE_THREE: i32 = 1_000; // 活三（威胁但非强制）
pub const SCORE_BLOCKED_THREE: i32 = 100; // 眠三
pub const SCORE_TWO: i32 = 100; // 活二
pub const SCORE_BLOCKED_TWO: i32 = 10; // 眠二

/// 复合威胁奖励分（跨方向组合）
/// 这些加在 evaluate_position 的总分之上，让 AI 的启发排序更准确
const BONUS_DOUBLE_THREE: i32 = 5_000;   // 双活三（两方向活三，含隔活三）—— 近乎必胜，介于冲四与活四之间
const BONUS_DOUBLE_FOUR: i32 = 30_000;   // 双冲四 / 双四（几乎必胜）
const BONUS_THREE_FOUR: i32 = 20_000;    // 活三 + 冲四
const BONUS_OPP_DOUBLE_THREE: i32 = 2_000; // 堵对方双活三
const BONUS_JUMP_THREE: i32 = 800;       // 隔活三（跳跃型活三，如 X_XX_ 或 XX_X_）
const BONUS_JUMP_FOUR: i32 = 1_200;      // 真跳冲四（可两端推进的断四）
const BONUS_DELAYED_DOUBLE: i32 = 2_400; // 延迟双威胁（下一拍可转双三/三四）

#[derive(Clone, Copy, Default)]
struct SideInfo {
    run: i32,
    immediate_open: bool,
    jump_run: i32,
    jump_open: bool,
}

#[derive(Clone, Copy, Default)]
struct DirectionEval {
    score: i32,
    real_four: bool,
    blocked_four: bool,
    live_three: bool,
    jump_three: bool,
    jump_four: bool,
    delayed_double: bool,
}

/// 评估特定位置的价值（供 MinimaxAi 调用）
///
/// 综合四个方向的单向棋型分，再叠加跨方向复合威胁奖励。
pub fn evaluate_position(board: &Board, row: usize, col: usize, color: Color) -> i32 {
    let mut score = 0;
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];

    let mut fours = 0i32;   // 活四方向数
    let mut bfours = 0i32;  // 冲四方向数
    let mut threes = 0i32;  // 活三方向数（含隔活三）
    let mut jump_fours = 0i32;
    let mut delayed_doubles = 0i32;

    for &(dr, dc) in &dirs {
        let eval = evaluate_direction(board, row, col, dr, dc, color);
        score += eval.score;
        if eval.jump_three {
            score += BONUS_JUMP_THREE;
        }
        if eval.jump_four {
            score += BONUS_JUMP_FOUR;
            jump_fours += 1;
        }
        if eval.delayed_double {
            delayed_doubles += 1;
        }

        if eval.real_four {
            fours += 1;
        } else if eval.blocked_four {
            bfours += 1;
        } else if eval.live_three {
            threes += 1;
        }
    }

    // 复合威胁加成
    // 允许“活四 + 其他方向威胁”继续叠加，避免把更强的复合杀点压平成普通活四。
    if bfours >= 2 {
        score += BONUS_DOUBLE_FOUR;
    } else if jump_fours >= 1 && threes >= 1 {
        score += BONUS_THREE_FOUR;
    } else if bfours >= 1 && threes >= 1 {
        score += BONUS_THREE_FOUR;
    } else if threes >= 2 && fours == 0 {
        // 双三本身仍要求当前点不是活四，否则其价值已被更高层威胁覆盖。
        score += BONUS_DOUBLE_THREE;
    }

    if delayed_doubles > 0 && fours == 0 {
        score += BONUS_DELAYED_DOUBLE * delayed_doubles;
    }

    score
}

/// 评估特定位置的防御价值（对方在此处落子的威胁）
/// 用于 AI 判断是否需要优先防守某个位置
#[allow(dead_code)]
pub fn evaluate_position_with_opp(board: &Board, row: usize, col: usize, color: Color) -> i32 {
    let opp = color.opponent();
    let my_score = evaluate_position(board, row, col, color);

    // 对方的组合威胁额外检测（只检测高危组合，减少计算量）
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];
    let mut opp_threes = 0i32;
    let mut opp_bfours = 0i32;
    for &(dr, dc) in &dirs {
        let eval = evaluate_direction(board, row, col, dr, dc, opp);
        if eval.real_four {
            // 对方有活四：即将五连，直接返回极高防御分
            return my_score + SCORE_FOUR * 2;
        } else if eval.blocked_four {
            opp_bfours += 1;
        } else if eval.live_three {
            opp_threes += 1;
        }
    }
    let opp_bonus = if opp_bfours >= 2 || (opp_bfours >= 1 && opp_threes >= 1) {
        BONUS_OPP_DOUBLE_THREE * 2
    } else if opp_threes >= 2 {
        BONUS_OPP_DOUBLE_THREE
    } else {
        0
    };
    my_score + opp_bonus
}

fn scan_side(board: &Board, row: usize, col: usize, dr: i32, dc: i32, color: Color) -> SideInfo {
    let mut r = row as i32 + dr;
    let mut c = col as i32 + dc;
    let mut info = SideInfo::default();

    while in_bounds(r, c) && board.get(r as usize, c as usize) == color {
        info.run += 1;
        r += dr;
        c += dc;
    }

    if in_bounds(r, c) && board.get(r as usize, c as usize) == Color::Empty {
        info.immediate_open = true;
        r += dr;
        c += dc;

        while in_bounds(r, c) && board.get(r as usize, c as usize) == color {
            info.jump_run += 1;
            r += dr;
            c += dc;
        }

        info.jump_open = in_bounds(r, c) && board.get(r as usize, c as usize) == Color::Empty;
    }

    info
}

fn evaluate_direction(
    board: &Board,
    row: usize,
    col: usize,
    dr: i32,
    dc: i32,
    color: Color,
) -> DirectionEval {
    let left = scan_side(board, row, col, -dr, -dc, color);
    let right = scan_side(board, row, col, dr, dc, color);

    let mut eval = DirectionEval::default();
    let straight = left.run + right.run + 1;
    let open_ends = (left.immediate_open as i32) + (right.immediate_open as i32);

    if straight >= 5 {
        eval.score = SCORE_FIVE;
        return eval;
    }

    if straight == 4 {
        if open_ends == 2 {
            eval.score = SCORE_FOUR;
            eval.real_four = true;
        } else {
            eval.score = SCORE_BLOCKED_FOUR;
            eval.blocked_four = true;
        }
        return eval;
    }

    let mut best_gap_score = 0i32;
    let mut gap_is_four = false;
    let mut gap_is_three = false;
    let mut gap_is_jump_three = false;
    let mut gap_is_jump_four = false;

    for (jump_run, opposite_open, jump_open) in [
        (left.jump_run, right.immediate_open, left.jump_open),
        (right.jump_run, left.immediate_open, right.jump_open),
    ] {
        if jump_run <= 0 {
            continue;
        }

        let total = straight + 1;
        let gap_open_ends = (opposite_open as i32) + (jump_open as i32);

        if total >= 4 {
            if gap_open_ends >= 2 {
                best_gap_score = best_gap_score.max(SCORE_BLOCKED_FOUR + 400);
                gap_is_four = true;
                gap_is_jump_four = true;
            } else if gap_open_ends == 1 && jump_run >= 2 {
                best_gap_score = best_gap_score.max(SCORE_BLOCKED_FOUR);
                gap_is_four = true;
            } else if gap_open_ends == 1 {
                best_gap_score = best_gap_score.max(SCORE_BLOCKED_THREE);
            }
        } else if total == 3 {
            if gap_open_ends == 2 {
                best_gap_score = best_gap_score.max(SCORE_THREE);
                gap_is_three = true;
                gap_is_jump_three = true;
            } else if gap_open_ends == 1 {
                best_gap_score = best_gap_score.max(SCORE_BLOCKED_THREE);
            }
        } else if total == 2 {
            if gap_open_ends == 2 {
                best_gap_score = best_gap_score.max(SCORE_TWO);
            } else if gap_open_ends == 1 {
                best_gap_score = best_gap_score.max(SCORE_BLOCKED_TWO);
            }
        }
    }

    match straight {
        3 => {
            if open_ends == 2 {
                eval.score = eval.score.max(SCORE_THREE);
                eval.live_three = true;
            } else if open_ends == 1 {
                eval.score = eval.score.max(SCORE_BLOCKED_THREE);
            }
        }
        2 => {
            if open_ends == 2 {
                eval.score = eval.score.max(SCORE_TWO);
            } else if open_ends == 1 {
                eval.score = eval.score.max(SCORE_BLOCKED_TWO);
            }
        }
        1 => {
            if open_ends == 2 && (left.jump_run > 0 || right.jump_run > 0) {
                eval.score = eval.score.max(SCORE_TWO);
            }
        }
        _ => {}
    }

    let delayed_double = open_ends == 2
        && straight <= 2
        && ((left.jump_run > 0 && right.jump_run > 0)
            || (left.jump_run >= 2 && right.run >= 1)
            || (right.jump_run >= 2 && left.run >= 1));
    if delayed_double {
        eval.score = eval.score.max(SCORE_THREE);
        eval.delayed_double = true;
    }

    if best_gap_score > eval.score {
        eval.score = best_gap_score;
    }
    if gap_is_four {
        eval.blocked_four = true;
        eval.live_three = false;
    }
    if gap_is_three {
        eval.live_three = true;
    }
    if gap_is_jump_three {
        eval.jump_three = true;
    }
    if gap_is_jump_four {
        eval.jump_four = true;
    }

    eval
}

/// 分析一条线上的棋型（供 MinimaxAi 调用）
/// 返回 (己方棋子数, 空格数, 是否被阻挡左, 是否被阻挡右)
pub fn analyze_line(
    board: &Board,
    row: usize,
    col: usize,
    dr: i32,
    dc: i32,
    color: Color,
) -> (i32, i32, bool, bool) {
    let mut count = 0; // 连续棋子数
    let mut empty = 0; // 连续空格数
    let mut left_blocked = false;
    let mut right_blocked = false;

    // 从落子点开始向两个方向延伸
    // 落子点本身算一个空位（即将落子）
    empty += 1;

    // 正向
    let (mut r, mut c) = (row as i32 + dr, col as i32 + dc);
    while in_bounds(r, c) {
        match board.get(r as usize, c as usize) {
            cell if cell == color => count += 1,
            Color::Empty => {
                if empty <= count + 1 {
                    empty += 1;
                } else {
                    break;
                }
            }
            _ => {
                right_blocked = true;
                break;
            }
        }
        r += dr;
        c += dc;
    }
    if !in_bounds(r, c) {
        right_blocked = true;
    }

    // 反向
    let (mut r, mut c) = (row as i32 - dr, col as i32 - dc);
    let mut reverse_count = 0;
    while in_bounds(r, c) {
        match board.get(r as usize, c as usize) {
            cell if cell == color => reverse_count += 1,
            Color::Empty => {
                if empty <= count + reverse_count + 1 {
                    empty += 1;
                } else {
                    break;
                }
            }
            _ => {
                left_blocked = true;
                break;
            }
        }
        r -= dr;
        c -= dc;
    }
    if !in_bounds(r, c) {
        left_blocked = true;
    }

    count += reverse_count;
    (count, empty, left_blocked, right_blocked)
}

/// 根据棋型匹配分数
pub fn match_pattern_score((count, empty, left_blocked, right_blocked): (i32, i32, bool, bool)) -> i32 {
    // 五连
    if count >= 4 && empty >= 5 {
        return SCORE_FIVE;
    }

    let blocked = if left_blocked && right_blocked {
        2
    } else if left_blocked || right_blocked {
        1
    } else {
        0
    };

    match (count, empty, blocked) {
        // 四子情况
        (3, 5, 0) => SCORE_FOUR,         // 活四（两边都空）
        (3, 4, 1) => SCORE_BLOCKED_FOUR, // 冲四
        (4, _, _) => SCORE_BLOCKED_FOUR, // 其他四子

        // 三子情况
        // 注：count=2 意味着已有2颗同色子紧靠落子点，加上落子点本身共3颗。
        // empty=5 是宽松窗口（两侧都有足够空间）；empty=4,blocked=0 是紧凑窗口
        // 但两端均未被对手或边界封堵，同样构成活三（可向两端延伸至五连）。
        (2, 5, 0) => SCORE_THREE,         // 活三（宽窗口）
        (2, 4, 0) => SCORE_THREE,         // 活三（紧凑窗口，两端均开放）
        (2, 4, 1) => SCORE_BLOCKED_THREE, // 眠三（一端封堵）
        (3, _, _) => SCORE_BLOCKED_THREE, // 其他三子

        // 两子情况
        (1, 5, 0) => SCORE_TWO,         // 活二
        (1, 4, 0) => SCORE_TWO,         // 活二（紧凑窗口，两端均开放）
        (1, 4, 1) => SCORE_BLOCKED_TWO, // 眠二
        (2, _, _) => SCORE_BLOCKED_TWO, // 其他两子

        // 其他
        _ => 0,
    }
}

fn in_bounds(r: i32, c: i32) -> bool {
    r >= 0 && r < BOARD_SIZE as i32 && c >= 0 && c < BOARD_SIZE as i32
}

/// 检测隔活三（跳跃型活三）：在某个方向上，落子后形成带有一个内部间隔的活三棋型。
///
/// 典型形态（P = 落子点，O = 己方子，_ = 空位）：
///   `_ O O P _ O _`  —— 落子点 P 在跳格左侧，正向 fwd1=0，反向 bwd1=2，
///                        跳格后正向看 fwd 侧有 1 子
///   `_ O P _ O O _`  —— 落子点 P 在跳格左侧，反向 bwd1=1，跳格后正向有 2 子
///   `_ O P O _ O _`  —— 落子点连接两边，其中一边在跳格后还有子
///
/// 方法：沿正方向和反方向各扫描"紧邻段"+"跳格后的延伸段"，
/// 满足（紧邻段之和 + 落子点 = 3，且有内部跳格，且两端开放）时认定为隔活三。
///
/// 返回值：若检测到隔活三，返回 BONUS_JUMP_THREE；否则返回 0。
pub fn jump_three_score(
    board: &Board,
    row: usize,
    col: usize,
    dr: i32,
    dc: i32,
    color: Color,
) -> i32 {
    let r0 = row as i32;
    let c0 = col as i32;

    // 辅助：沿一个方向扫描，返回
    //   seg1: 紧邻落子点的连续同色子数
    //   seg2: 跳过一个空格后的连续同色子数（没有跳格则为 0）
    //   end_open: 扫描末端（包括跳格后段末端）是否开放
    let scan = |step_r: i32, step_c: i32| -> (i32, i32, bool) {
        let mut r = r0 + step_r;
        let mut c = c0 + step_c;
        let mut seg1 = 0i32;
        let mut seg2 = 0i32;

        // 第一段连续同色子
        while in_bounds(r, c) && board.get(r as usize, c as usize) == color {
            seg1 += 1;
            r += step_r;
            c += step_c;
        }

        // 尝试跳一个空格
        if in_bounds(r, c) && board.get(r as usize, c as usize) == Color::Empty {
            let gap_r = r;
            let gap_c = c;
            // 跳过这个空格
            let mut rr = gap_r + step_r;
            let mut cc = gap_c + step_c;
            // 跳格后的连续同色子
            while in_bounds(rr, cc) && board.get(rr as usize, cc as usize) == color {
                seg2 += 1;
                rr += step_r;
                cc += step_c;
            }
            // 末端开放：跳格后段末尾是空位（边界外 = 不开放）
            let end_open = in_bounds(rr, cc)
                && board.get(rr as usize, cc as usize) == Color::Empty;
            (seg1, seg2, end_open)
        } else {
            // 没有跳格：末端是否开放（边界外或对手棋子 → 不开放）
            let end_open = in_bounds(r, c) && board.get(r as usize, c as usize) == Color::Empty;
            (seg1, 0, end_open)
        }
    };

    // 正向（落子点出发沿 +dr/+dc）
    let (fwd1, fwd2, fwd_open) = scan(dr, dc);
    // 反向（落子点出发沿 -dr/-dc）
    let (bwd1, bwd2, bwd_open) = scan(-dr, -dc);

    // 隔活三判断：
    // 总子数（含落子点） = bwd1 + 1 + fwd1 + bwd2 + fwd2 必须为 3
    // 且必须至少有一侧存在内部跳格（fwd2>0 或 bwd2>0）
    // 且两端均开放
    let total = bwd1 + 1 + fwd1 + bwd2 + fwd2;
    let has_jump = fwd2 > 0 || bwd2 > 0;

    if total == 3 && has_jump && fwd_open && bwd_open {
        BONUS_JUMP_THREE
    } else {
        0
    }
}
