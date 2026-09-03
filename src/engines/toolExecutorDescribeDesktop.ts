import type { ToolAction } from './toolExecutorTypes';
import type { ToolActionDescription } from './toolExecutorDescribe';

export type DesktopToolAction = Extract<
  ToolAction,
  {
    kind:
      | 'runAndroidShell'
      | 'editDesktopFileText'
      | 'searchDesktopFiles'
      | 'readDesktopFileContext'
      | 'replaceDesktopFileLines'
      | 'listDesktopWorkspaces'
      | 'listDesktopFiles'
      | 'readDesktopFile'
      | 'writeDesktopFile'
      | 'createDesktopDirectory'
      | 'deleteDesktopPath'
      | 'moveDesktopPath'
      | 'runDesktopCommand'
      | 'runDesktopCommandSequence'
      | 'startDesktopCommand'
      | 'listDesktopCommandSessions'
      | 'stopDesktopCommand'
      | 'syncDesktopWorkspaceFromDisk'
      | 'syncDesktopWorkspaceToDisk';
  }
>;

/**
 * Natural-language descriptions for the desktop (local-machine) tool actions — file read/edit,
 * directory and path operations, command/terminal sessions, and workspace disk sync. This is
 * **description only**: pure field formatting with no side effects. Desktop permissions, the
 * executor, sync semantics, and the `window.polarisDesktopLocal` boundary live elsewhere and are
 * untouched. The central `describeToolAction` dispatcher delegates these kinds here.
 */
export function describeDesktopToolAction(action: DesktopToolAction): ToolActionDescription {
  switch (action.kind) {
    case 'runAndroidShell':
      return {
        kind: action.kind,
        title: '运行手机 Shell',
        summary: `运行手机 Shell · ${action.targetLabel || action.command}`,
        targetLabel: action.targetLabel ?? action.command
      };
    case 'editDesktopFileText':
      return {
        kind: action.kind,
        title: '已局部替换本机文件',
        summary: `${action.targetLabel || action.filePath} · 局部替换`,
        targetLabel: action.targetLabel
      };
    case 'searchDesktopFiles':
      return {
        kind: action.kind,
        title: '搜索本机文件',
        summary: `${action.targetLabel || action.query} · 搜索`,
        targetLabel: action.targetLabel
      };
    case 'readDesktopFileContext':
      return {
        kind: action.kind,
        title: '读取本机文件上下文',
        summary: `${action.targetLabel || action.filePath} · 上下文`,
        targetLabel: action.targetLabel
      };
    case 'replaceDesktopFileLines':
      return {
        kind: action.kind,
        title: '已按行替换本机文件',
        summary: `${action.targetLabel || action.filePath} · ${action.startLine}${action.endLine ? `-${action.endLine}` : ''} 行`,
        targetLabel: action.targetLabel
      };
    case 'listDesktopWorkspaces':
      return {
        kind: action.kind,
        title: '读取本机工作区',
        summary: `读取已授权本机工作区${action.targetLabel ? ` · ${action.targetLabel}` : ''}`,
        targetLabel: action.targetLabel
      };
    case 'listDesktopFiles':
      return {
        kind: action.kind,
        title: '读取本机目录',
        summary: `读取本机目录 · ${action.path || '.'}`,
        targetLabel: action.targetLabel ?? action.path
      };
    case 'readDesktopFile':
      return {
        kind: action.kind,
        title: '读取本机文件',
        summary: `读取本机文件 · ${action.filePath}`,
        targetLabel: action.targetLabel ?? action.filePath
      };
    case 'writeDesktopFile':
      return {
        kind: action.kind,
        title: '写入本机文件',
        summary: `写入本机文件 · ${action.filePath}`,
        targetLabel: action.targetLabel ?? action.filePath
      };
    case 'createDesktopDirectory':
      return {
        kind: action.kind,
        title: '创建本机文件夹',
        summary: `创建本机文件夹 · ${action.path}`,
        targetLabel: action.targetLabel ?? action.path
      };
    case 'deleteDesktopPath':
      return {
        kind: action.kind,
        title: '删除本机路径',
        summary: `删除本机路径 · ${action.path}`,
        targetLabel: action.targetLabel ?? action.path
      };
    case 'moveDesktopPath':
      return {
        kind: action.kind,
        title: '移动本机路径',
        summary: `移动本机路径 · ${action.fromPath} -> ${action.toPath}`,
        targetLabel: action.targetLabel ?? action.toPath
      };
    case 'runDesktopCommand':
      return {
        kind: action.kind,
        title: '运行本机命令',
        summary: `运行本机命令 · ${[action.command, ...(action.args ?? [])].join(' ')}`,
        targetLabel: action.targetLabel ?? action.command
      };
    case 'runDesktopCommandSequence':
      return {
        kind: action.kind,
        title: '运行本机命令流程',
        summary: `运行本机命令流程 · ${action.steps.length} 步`,
        targetLabel: action.targetLabel ?? action.steps.map((step) => step.label ?? step.command).join(' / ')
      };
    case 'startDesktopCommand':
      return {
        kind: action.kind,
        title: '启动本机终端',
        summary: `启动本机终端 · ${[action.command, ...(action.args ?? [])].join(' ')}`,
        targetLabel: action.targetLabel ?? action.command
      };
    case 'listDesktopCommandSessions':
      return {
        kind: action.kind,
        title: '查看本机终端',
        summary: '查看本机终端会话',
        targetLabel: action.targetLabel
      };
    case 'stopDesktopCommand':
      return {
        kind: action.kind,
        title: '停止本机终端',
        summary: `停止本机终端 · ${action.sessionId}`,
        targetLabel: action.targetLabel ?? action.sessionId
      };
    case 'syncDesktopWorkspaceFromDisk':
      return {
        kind: action.kind,
        title: '从电脑读入工作区',
        summary: `从真实电脑文件夹读入到 Polaris${action.projectId ? ` · ${action.projectId}` : ''}`,
        targetLabel: action.targetLabel ?? action.projectId ?? action.rootId
      };
    case 'syncDesktopWorkspaceToDisk':
      return {
        kind: action.kind,
        title: '送到电脑工作区',
        summary: `把 Polaris 工作区送到真实电脑文件夹${action.projectId ? ` · ${action.projectId}` : ''}`,
        targetLabel: action.targetLabel ?? action.projectId ?? action.rootId
      };
  }
}
