use serde::{Deserialize, Serialize};

use crate::util::now_time;

/// 更新来源
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    Npm,
    Winget,
    Pip,
    Openclaw,
}

impl Source {
    pub fn label(&self) -> &'static str {
        match self {
            Source::Npm => "npm 全局包",
            Source::Winget => "winget 软件",
            Source::Pip => "pip 包",
            Source::Openclaw => "OpenClaw",
        }
    }

}

/// 一条可更新项
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateItem {
    /// 全局唯一标识，形如 `npm:openclaw`
    pub id: String,
    pub source: Source,
    pub name: String,
    pub current: String,
    pub latest: String,
    /// npm 的目标 tag（dev / beta / latest）
    pub tag: Option<String>,
    /// winget 的包 Id，安装时用
    pub pkg_id: Option<String>,
    /// 应用图标 PNG data URL，前端 fetch_icons 后填充
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
}

/// 已安装程序的 DisplayIcon 缓存条目
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IconEntry {
    pub name: String,
    pub icon: String,
}

/// 通知偏好
/// - mode: native（Windows 系统通知）/ app（应用内独立窗口）
/// - position: top-left / top-center / top-right / bottom-left / bottom-center / bottom-right
/// - duration_ms: 单条 toast 显示时长
/// - opacity: 0~100
/// - max_stack: 同时显示的最大条数
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotifyPrefs {
    pub mode: String,
    pub position: String,
    pub duration_ms: u32,
    pub opacity: u8,
    pub max_stack: u8,
}

/// 环境探测结果
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EnvStatus {
    pub npm: bool,
    pub winget: bool,
    pub pip: bool,
    pub openclaw: bool,
    pub is_admin: bool,
}

/// 推送给前端的日志行
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LogLine {
    pub level: String,
    pub text: String,
    pub at: String,
}

impl LogLine {
    pub fn new(level: &str, text: impl Into<String>) -> Self {
        Self {
            level: level.to_string(),
            text: text.into(),
            at: now_time(),
        }
    }
}

/// 推送给前端的进度
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Progress {
    pub done: usize,
    pub total: usize,
    pub current_id: String,
    pub current_name: String,
    pub status: String,
}

/// 单项执行结果
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ItemResult {
    pub id: String,
    pub name: String,
    pub ok: bool,
}
