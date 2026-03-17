use super::*;

fn run_tt_self_play_probe(label: &str, black_cfg: MinimaxConfig, white_cfg: MinimaxConfig, max_plies: usize) {
    let mut board = Board::new();
    let mut black = MinimaxAi::new(black_cfg);
    let mut white = MinimaxAi::new(white_cfg);
    let mut to_move = Color::Black;
    let mut black_peak = 0usize;
    let mut white_peak = 0usize;

    println!("=== {label} ===");
    for ply in 0..max_plies {
        let ai = if to_move == Color::Black { &mut black } else { &mut white };
        let mv = ai.take_turn(&board).expect("self-play should always find a move");
        assert!(board.place(mv.0, mv.1, to_move), "illegal move at ply {ply}: {mv:?}");

        let stats = ai.tt_stats();
        if to_move == Color::Black {
            black_peak = black_peak.max(stats.occupied);
        } else {
            white_peak = white_peak.max(stats.occupied);
        }

        println!(
            "ply={} side={:?} move={:?} tt_occ={}/{} coll={} same={} reject={}",
            ply + 1,
            to_move,
            mv,
            stats.occupied,
            stats.capacity,
            stats.collision_replacements,
            stats.same_key_updates,
            stats.depth_rejects,
        );

        if board.check_win(mv.0, mv.1, to_move) {
            println!("winner={:?} at ply={}", to_move, ply + 1);
            break;
        }

        to_move = to_move.opponent();
    }

    let black_stats = black.tt_stats();
    let white_stats = white.tt_stats();
    println!(
        "summary black peak={}/{} final={} coll={} same={} reject={}",
        black_peak,
        black_stats.capacity,
        black_stats.occupied,
        black_stats.collision_replacements,
        black_stats.same_key_updates,
        black_stats.depth_rejects,
    );
    println!(
        "summary white peak={}/{} final={} coll={} same={} reject={}",
        white_peak,
        white_stats.capacity,
        white_stats.occupied,
        white_stats.collision_replacements,
        white_stats.same_key_updates,
        white_stats.depth_rejects,
    );
}

#[test]
#[ignore]
fn probe_tt_usage_hard_self_play() {
    run_tt_self_play_probe(
        "hard-self-play",
        MinimaxConfig { max_depth: 12, time_limit_ms: 4_000, player: Color::Black },
        MinimaxConfig { max_depth: 12, time_limit_ms: 4_000, player: Color::White },
        20,
    );
}

#[test]
#[ignore]
fn probe_tt_usage_master_self_play() {
    run_tt_self_play_probe(
        "master-self-play",
        MinimaxConfig { max_depth: 20, time_limit_ms: 12_000, player: Color::Black },
        MinimaxConfig { max_depth: 20, time_limit_ms: 12_000, player: Color::White },
        24,
    );
}
