pub mod npm;
pub mod openclaw;
pub mod pip;
pub mod winget;

use crate::model::{Source, UpdateItem};

/// 日志回调：level 取 info / ok / warn / err / cmd
pub type LogFn<'a> = &'a dyn Fn(&str, String);

pub trait UpdateSource {
    fn source(&self) -> Source;

    /// 该源在当前机器是否可用
    fn available(&self) -> bool;

    /// 探测可更新项
    fn check(&self, log: LogFn) -> Vec<UpdateItem>;

    /// 执行单个更新，返回是否成功
    fn update(&self, item: &UpdateItem, log: LogFn) -> bool;
}
