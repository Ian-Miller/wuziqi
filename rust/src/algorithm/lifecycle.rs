use std::sync::atomic::{AtomicBool, Ordering};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SearchAbort {
    Timeout,
    Cancelled,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum TurnStatus {
    Completed,
    Timeout,
    Cancelled,
    NoMove,
}

impl From<SearchAbort> for TurnStatus {
    fn from(value: SearchAbort) -> Self {
        match value {
            SearchAbort::Timeout => TurnStatus::Timeout,
            SearchAbort::Cancelled => TurnStatus::Cancelled,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct TurnOutcome {
    pub(crate) best_move: Option<(usize, usize)>,
    pub(crate) status: TurnStatus,
    pub(crate) completed_depth: i32,
    pub(crate) elapsed_ms: u64,
    pub(crate) node_count: u64,
}

#[derive(Clone, Copy)]
pub(crate) struct HeartbeatConfig {
    pub(crate) report_interval_ms: u64,
    pub(crate) min_delta_percent: i32,
    pub(crate) stop_check_interval: u64,
    pub(crate) progress_check_interval: u64,
    pub(crate) progress_cap: i32,
}

pub(crate) struct ProgressState {
    last_report_ms: u64,
    last_report_percent: i32,
}

impl ProgressState {
    pub(crate) fn new() -> Self {
        Self {
            last_report_ms: 0,
            last_report_percent: -1,
        }
    }
}

pub(crate) trait SearchLifecycle {
    fn stop_flag(&self) -> &AtomicBool;
    fn elapsed_ms(&self) -> u64;
    fn time_limit_ms(&self) -> u64;
    fn heartbeat_config(&self) -> HeartbeatConfig;

    fn abort_reason(&self) -> Option<SearchAbort> {
        if self.stop_flag().load(Ordering::Acquire) {
            return Some(SearchAbort::Cancelled);
        }
        if self.elapsed_ms() > self.time_limit_ms() {
            return Some(SearchAbort::Timeout);
        }
        None
    }

    fn maybe_report_progress(
        &self,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress_percent: i32,
        progress_state: &mut ProgressState,
        force: bool,
    ) {
        let config = self.heartbeat_config();
        let p = progress_percent.clamp(0, 100);
        let now = self.elapsed_ms();
        let should_emit = force
            || (progress_state.last_report_ms == 0)
            || (now.saturating_sub(progress_state.last_report_ms) >= config.report_interval_ms
                && (p - progress_state.last_report_percent).abs() >= config.min_delta_percent);
        if should_emit {
            if let Some(cb) = on_progress.as_deref_mut() {
                cb(p);
            }
            progress_state.last_report_ms = now;
            progress_state.last_report_percent = p;
        }
    }

    fn heartbeat(
        &self,
        tick: u64,
        on_progress: &mut Option<&mut dyn FnMut(i32)>,
        progress_state: &mut ProgressState,
    ) -> Result<(), SearchAbort> {
        if let Some(reason) = self.abort_reason() {
            return Err(reason);
        }

        let config = self.heartbeat_config();
        if tick % config.stop_check_interval == 0 {
            if let Some(reason) = self.abort_reason() {
                return Err(reason);
            }
        }

        if tick % config.progress_check_interval == 0 {
            let progress = ((self.elapsed_ms() as f64 / self.time_limit_ms().max(1) as f64)
                * config.progress_cap as f64)
                .round() as i32;
            self.maybe_report_progress(
                on_progress,
                progress.clamp(0, config.progress_cap),
                progress_state,
                false,
            );
        }

        Ok(())
    }

    fn invalidate_search(&self) {
        self.stop_flag().store(true, Ordering::Release);
    }

    fn validate_search(&self) {
        self.stop_flag().store(false, Ordering::Release);
    }
}