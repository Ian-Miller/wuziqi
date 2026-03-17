use std::sync::atomic::{AtomicBool, AtomicI32};
use std::sync::Arc;
use std::time::Instant;

use crate::board::Color;

#[derive(Clone, Copy)]
pub struct MctsConfig {
    pub player: Color,
    pub time_limit_ms: u64,
    pub exploration_c: f64,
    pub max_children: usize,
}

impl MctsConfig {
    pub fn easy(player: Color) -> Self {
        Self {
            player,
            time_limit_ms: 500,
            exploration_c: 1.8,
            max_children: super::MAX_CHILDREN_EASY,
        }
    }

    pub fn medium(player: Color) -> Self {
        Self {
            player,
            time_limit_ms: 1500,
            exploration_c: 1.2,
            max_children: super::MAX_CHILDREN_MEDIUM,
        }
    }
}

pub struct MctsAi {
    pub(super) config: MctsConfig,
    pub(super) should_stop: Arc<AtomicBool>,
    pub(super) start_time: Instant,
    pub(super) easy_mode: bool,
    pub(super) mistake_prob: f64,
    pub(super) narrow_vision_prob: f64,
    pub(super) sample_temperature: f64,
    pub(super) sample_top_n: usize,
    pub(super) rng_state: u64,
    pub best_move_encoded: Arc<AtomicI32>,
}