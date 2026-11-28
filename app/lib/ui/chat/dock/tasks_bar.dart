// The task bar above the composer (DSH's `conversation.input.dock`): the
// conversations with work in them — running right now, or left half done when
// the engine stopped — plus the one on screen, tinted, so the list says where
// you are. Nothing else: it is a switcher for work, not a chat list.
//
// The bar follows the dock's rules: one row high while it has something to
// say, zero high otherwise, expanding in place into the list.

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/l10n.dart';
import '../../../state/session_attention_state.dart';
import '../../common/expand_reveal.dart';
import '../../common/scroll_edge_fade.dart';
import '../../common/status_dot.dart';
import '../../features/feature_widgets.dart' show openSessionInChat;
import '../../theme/app_icons.dart';
import '../../theme/theme.dart';
import 'dock_pill.dart';

/// What a row says: work in flight (or cut short), or the conversation on
/// screen. The drawer's other two markers — waiting on you, unread — stay in the
/// drawer; this bar is about work.
enum DockTaskKind {
  waiting,
  running,
  finished,

  /// The conversation on screen: listed, tinted, not a destination.
  idle,
}

/// One conversation in the bar.
@immutable
class DockTask {
  const DockTask({
    required this.sessionId,
    required this.title,
    required this.kind,
    required this.updatedAt,
    this.pending,
    this.stopped = false,
    this.current = false,
  });

  final String sessionId;
  final String title;
  final DockTaskKind kind;
  final DateTime updatedAt;

  /// What it waits for, when [kind] is [DockTaskKind.waiting].
  final PendingKind? pending;

  /// [DockTaskKind.running] but not running any more: the engine stopped and
  /// closed the turn, so the work is unfinished rather than in flight.
  final bool stopped;

  /// The conversation on screen: listed, but tinted and not a destination.
  final bool current;

  @override
  bool operator ==(Object other) =>
      other is DockTask &&
      other.sessionId == sessionId &&
      other.kind == kind &&
      other.title == title &&
      other.pending == pending &&
      other.stopped == stopped &&
      other.current == current;

  @override
  int get hashCode => Object.hash(sessionId, kind, title, pending, stopped, current);
}

/// The bar's content, derived from what the drawer already knows: the session
/// rows, the pending interactions, the unread set, and the conversations whose
/// last turn the engine's restart cut short (they are idle, so no dot says so).
/// Pure, so the tests can pin the order without a device.
abstract final class DockTasks {
  /// The bar's rows, in the drawer's own order (`sessions` arrives sorted by
  /// recency): the conversations with work in them — running now, or left half
  /// done when the engine stopped — and, when the one on screen has no work of
  /// its own, that one last. Order never depends on which row you tap, so a
  /// switch moves nothing and the tinted row simply follows you.
  static List<DockTask> of({
    required List<SessionSummary> sessions,
    required Set<String> interrupted,
    required String? current,
    required String untitled,
  }) {
    final out = <DockTask>[];
    DockTask? here;
    for (final session in sessions) {
      final onScreen = session.sessionId == current;
      final stopped = !session.running && interrupted.contains(session.sessionId);
      final active = session.running || stopped;
      // Everything without work stays out, except the conversation on screen.
      if (!active && !onScreen) continue;
      final title = session.title?.trim();
      final task = DockTask(
        sessionId: session.sessionId,
        title: title == null || title.isEmpty ? untitled : title,
        // A conversation with work keeps its row; the tinted one that has no
        // work of its own is a marker, not a task.
        kind: active ? DockTaskKind.running : DockTaskKind.idle,
        updatedAt: session.updatedAt,
        stopped: stopped,
        current: onScreen,
      );
      if (active) {
        out.add(task);
      } else {
        here = task;
      }
    }
    // The marker sits at the bottom, so tapping a row above it changes nothing
    // about the order: this row is the only one that comes or goes.
    if (here != null) out.add(here);
    return out;
  }

  /// Whether the list holds work (the conversation on screen alone is not a
  /// reason for the bar to exist).
  static bool hasWork(List<DockTask> tasks) => tasks.any((t) => t.kind == DockTaskKind.running);

  /// 「2 个在跑 · 1 个已中断」, empty parts left out.
  static String summary(List<DockTask> tasks, L10n l10n) {
    final running = tasks.where((t) => t.kind == DockTaskKind.running && !t.stopped).length;
    final stopped = tasks.where((t) => t.stopped).length;
    return [
      if (running > 0) l10n.dockTasksRunning(running),
      if (stopped > 0) l10n.dockTasksInterrupted(stopped),
    ].join(' · ');
  }
}

/// 「◫ 2 个在跑 ▰ ˄」; a tap on the bar opens the list, and with a single
/// conversation in it, goes straight there.
class TasksBar extends ConsumerStatefulWidget {
  const TasksBar({super.key, required this.tasks, this.initiallyOpen = false});

  /// The rows: work first in the drawer's order, then the conversation on
  /// screen when it has no work of its own.
  final List<DockTask> tasks;

  final bool initiallyOpen;

  /// How tall the open list may get: at most this share of the screen.
  static const maxListShare = 0.34;
  static const maxList = 224.0;

  @override
  ConsumerState<TasksBar> createState() => _TasksBarState();
}

class _TasksBarState extends ConsumerState<TasksBar> {
  late bool _open = widget.initiallyOpen;

  void _go(DockTask task) {
    HapticFeedback.selectionClick();
    openSessionInChat(context, ref, task.sessionId);
  }

  void _toggle() {
    HapticFeedback.selectionClick();
    setState(() => _open = !_open);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dsh = DshColors.of(context);
    final tasks = widget.tasks;
    // One row and it is not the one you are in: the bar is a shortcut to it.
    final single = tasks.length == 1 && !tasks.single.current;
    final summary = DockTasks.summary(tasks, l10n);
    final header = Semantics(
      button: true,
      expanded: single ? null : _open,
      label: summary,
      onTapHint: single ? l10n.shellCurrentlyRunning : (_open ? l10n.commonCollapse : l10n.commonMore),
      excludeSemantics: true,
      child: InkWell(
        onTap: single ? () => _go(tasks.single) : _toggle,
        customBorder: const RoundedSuperellipseBorder(borderRadius: DockMetrics.shape),
        child: DockRow(
          leading: DockGlyph.icon(AppIcons.jobs, color: colors.onSurfaceVariant),
          content: Text(
            summary,
            style: DockText.title(context),
            maxLines: 1,
            softWrap: false,
            overflow: TextOverflow.ellipsis,
          ),
          accessory: single ? null : _Counts(tasks: tasks),
          trailing: single
              ? null
              : DockGlyph(
                  child: AnimatedRotation(
                    turns: _open ? 0.5 : 0,
                    duration: AppMotion.of(context, AppMotion.standard),
                    curve: AppMotion.std,
                    child: Icon(AppIcons.collapse, size: DockGlyph.iconSize, color: dsh.textTertiary),
                  ),
                ),
        ),
      ),
    );
    final maxList = (MediaQuery.sizeOf(context).height * TasksBar.maxListShare).clamp(0.0, TasksBar.maxList);
    return GlassSurface(
      tier: GlassTier.chrome,
      borderRadius: DockMetrics.shape,
      grouped: true,
      child: Material(
        type: MaterialType.transparency,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            header,
            ExpandReveal(
              open: _open && !single,
              duration: AppMotion.standard,
              curve: AppMotion.std,
              child: ConstrainedBox(
                constraints: BoxConstraints(maxHeight: maxList),
                child: ScrollEdgeFade(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.fromLTRB(DockMetrics.edge, 0, DockMetrics.edge, DockMetrics.edge),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final task in tasks)
                          DockTaskRow(
                            key: ValueKey(task.sessionId),
                            task: task,
                            onTap: task.current ? null : () => _go(task),
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The bars behind the text: one segment per kind, widest for what waits on you.
class _Counts extends StatelessWidget {
  const _Counts({required this.tasks});

  final List<DockTask> tasks;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final dsh = DshColors.of(context);
    final running = tasks.where((t) => t.kind == DockTaskKind.running && !t.stopped).length;
    final stopped = tasks.where((t) => t.stopped).length;
    const barWidth = 28.0;
    const barThickness = 3.0;
    Widget segment(int value, Color color) => Expanded(
      flex: value,
      child: Container(
        height: barThickness,
        decoration: ShapeDecoration(color: color, shape: const StadiumBorder()),
      ),
    );
    return SizedBox(
      width: barWidth,
      child: Row(
        children: [
          if (running > 0) segment(running, colors.primary),
          if (stopped > 0) segment(stopped, dsh.warning),
        ],
      ),
    );
  }
}

/// One conversation: its marker, its title, and what it is doing.
class DockTaskRow extends StatelessWidget {
  const DockTaskRow({super.key, required this.task, required this.onTap});

  final DockTask task;

  /// Null for the conversation already on screen: it is a marker here.
  final VoidCallback? onTap;

  static const height = 34.0;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dsh = DshColors.of(context);
    final (StatusTone tone, String state) = switch (task.kind) {
      DockTaskKind.waiting => (StatusTone.warn, task.pending?.label ?? l10n.shellUnread),
      DockTaskKind.running => task.stopped
          ? (StatusTone.warn, l10n.dockTasksStopped)
          : (StatusTone.busy, l10n.shellCurrentlyRunning),
      DockTaskKind.finished => (StatusTone.idle, l10n.shellUnread),
      DockTaskKind.idle => (StatusTone.idle, ''),
    };
    // The conversation on screen is ticked with a quiet fill, the way the
    // drawer marks its row: it is where you already are, not a destination.
    final current = task.current;
    final wording = current ? l10n.dockTasksCurrent : state;
    return Semantics(
      button: !current,
      selected: current,
      label: current ? '${task.title}, $wording' : '${task.title}, $state',
      excludeSemantics: true,
      child: DecoratedBox(
        decoration: current
            ? ShapeDecoration(
                color: colors.onSurface.withValues(alpha: 0.07),
                shape: const RoundedSuperellipseBorder(borderRadius: DockMetrics.shape),
              )
            : const BoxDecoration(),
        child: InkWell(
          onTap: onTap,
          customBorder: const RoundedSuperellipseBorder(borderRadius: DockMetrics.shape),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: height),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: DockMetrics.edge, vertical: 6),
              child: Row(
                children: [
                  SizedBox(
                    width: DockMetrics.glyph,
                    child: Center(child: StatusDot(tone: tone, size: 7)),
                  ),
                  const SizedBox(width: DockMetrics.glyphGap),
                  Expanded(
                    child: Text(
                      task.title,
                      style: current
                          ? DockText.title(context).copyWith(fontWeight: FontWeight.w600)
                          : DockText.title(context),
                      maxLines: 1,
                      softWrap: false,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (wording.isNotEmpty) ...[
                    const SizedBox(width: DockMetrics.trailingGap),
                    Text(
                      wording,
                      style: DockText.detail(
                        context,
                        color: task.kind == DockTaskKind.waiting || task.stopped || current
                            ? dsh.warning
                            : colors.onSurfaceVariant,
                      ),
                      maxLines: 1,
                      softWrap: false,
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
