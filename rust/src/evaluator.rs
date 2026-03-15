use crate::board::{Board, Color, BOARD_SIZE};

/// 棋型分数
pub const SCORE_FIVE: i32 = 100_000; // 五连（必胜）
pub const SCORE_FOUR: i32 = 10_000; // 活四
pub const SCORE_BLOCKED_FOUR: i32 = 1_000; // 冲四
pub const SCORE_THREE: i32 = 1_000; // 活三
pub const SCORE_BLOCKED_THREE: i32 = 100; // 眠三
pub const SCORE_TWO: i32 = 100; // 活二
pub const SCORE_BLOCKED_TWO: i32 = 10; // 眠二

/// 复合威胁奖励分（跨方向组合）
/// 这些加在 evaluate_position 的总分之上，让 AI 的启发排序更准确
const BONUS_DOUBLE_THREE: i32 = 3_000;   // 双活三（两个方向都有活三，含隔活三）
const BONUS_DOUBLE_FOUR: i32 = 30_000;   // 双冲四 / 双四（几乎必胜）
const BONUS_THREE_FOUR: i32 = 20_000;    // 活三 + 冲四
const BONUS_OPP_DOUBLE_THREE: i32 = 2_000; // 堵对方双活三
const BONUS_JUMP_THREE: i32 = 800;       // 隔活三（跳跃型活三，如 X_XX_ 或 XX_X_）

/// 评估特定位置的价值（供 GomokuAi 调用）
///
/// 综合四个方向的单向棋型分，再叠加跨方向复合威胁奖励。
pub fn evaluate_position(board: &Board, row: usize, col: usize, color: Color) -> i32 {
    let mut score = 0;
    let dirs = [(0i32, 1i32), (1, 0), (1, 1), (1, -1)];

    let mut fours = 0i32;   // 活四方向数
    let mut bfours = 0i32;  // 冲四方向数
    let mut threes = 0i32;  // 活三方向数（含隔活三）

    for &(dr, dc) in &dirs {
        let pattern = analyze_line(board, row, col, dr, dc, color);
        let s = match_pattern_score(pattern);
        score += s;

        // 统计各棋型方向数，用于复合威胁检测
        if s >= SCORE_FOUR {
            fours += 1;
        } else if s >= SCORE_BLOCKED_FOUR {
            // emp>=5 在 count==2 时实际不可达（最大为4），用 emp>=4 检测活三
            let (cnt, emp, lb, rb) = pattern;
            let is_active_three = cnt == 2 && emp >= 4 && !lb && !rb;
            if is_active_three { threes += 1; } else { bfours += 1; }
        } else {
            // 检测隔活三（跳跃型活三），并加分
            let jump = jump_three_score(board, row, col, dr, dc, color);
            if jump > 0 {
                score += jump;
                threes += 1; // 隔活三也算活三，参与复合威胁检测
            }
        }
    }

    // 复合威胁加成
    if fours == 0 {
        if bfours >= 2 {
            score += BONUS_DOUBLE_FOUR;
        } else if bfours >= 1 && threes >= 1 {
            score += BONUS_THREE_FOUR;
        } else if threes >= 2 {
            score += BONUS_DOUBLE_THREE;
        }
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
        let pattern = analyze_line(board, row, col, dr, dc, opp);
        let s = match_pattern_score(pattern);
        if s >= SCORE_FOUR {
            // 对方有活四：即将五连，直接返回极高防御分
            return my_score + SCORE_FOUR * 2;
        } else if s >= SCORE_BLOCKED_FOUR {
            let (cnt, emp, lb, rb) = pattern;
            let is_three = cnt == 2 && emp >= 4 && !lb && !rb;
            if is_three { opp_threes += 1; } else { opp_bfours += 1; }
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

/// 分析一条线上的棋型（供 GomokuAi 调用）
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
        (2, 5, 0) => SCORE_THREE,         // 活三
        (2, 4, 1) => SCORE_BLOCKED_THREE, // 眠三
        (3, _, _) => SCORE_BLOCKED_THREE, // 其他三子

        // 两子情况
        (1, 5, 0) => SCORE_TWO,         // 活二
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
