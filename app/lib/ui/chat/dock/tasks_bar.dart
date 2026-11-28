// The task bar above the composer (DSH's `conversation.input.dock`): the
// conversations that are running right now, the ones that wait on you (an
// approval, a plan review, a question), and the ones that moved on while you
// were elsewhere. They are the same three markers the drawer puts at the end
// of a row; here they sit in one bar, and a tap brings that conversation back.
//
// The bar follows the dock's rules: one row high while it has something to
// say, zero high otherwise, expanding in place into the whole list.

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

/// Why a conversation is in the bar, strongest first: something that waits on
/// you comes before work in flight, which comes before a conversation that
/// finished while you were away.
enum DockTaskKind { waiting, running, finished }

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

  @override
  bool operator ==(Object other) =>
      other is DockTask &&
      other.sessionId == sessionId &&
      other.kind == kind &&
      other.title == title &&
      other.pending == pending &&
      other.stopped == stopped;

  @override
  int get hashCode => Object.hash(sessionId, kind, title, pending, stopped);
}

/// The bar's content, derived from what the drawer already knows: the session
/// rows, the pending interactions, the unread set, and the conversations whose
/// last turn the engine's restart cut short (they are idle, so no dot says so).
/// Pure, so the tests can pin the order without a device.
abstract final class DockTasks {
  static List<DockTask> of({
    required List<SessionSummary> sessions,
    required Map<String, PendingKind> pending,
    required Set<String> unread,
    required Set<String> interrupted,
    required String? current,
    required String untitled,
  }) {
    final out = <DockTask>[];
    for (final session in sessions) {
      // The conversation on screen already shows its own state.
      if (session.sessionId == current) continue;
      final waiting = pending[session.sessionId];
      // An interrupted turn is unfinished work, not a finished one: it belongs
      // with the running conversations so it reads as something still owed.
      final stopped = waiting == null && !session.running && interrupted.contains(session.sessionId);
      final DockTaskKind kind;
      if (waiting != null) {
        kind = DockTaskKind.waiting;
      } else if (session.running || stopped) {
        kind = DockTaskKind.running;
      } else if (unread.contains(session.sessionId)) {
        kind = DockTaskKind.finished;
      } else {
        continue;
      }
      final title = session.title?.trim();
      out.add(
        DockTask(
          sessionId: session.sessionId,
          title: title == null || title.isEmpty ? untitled : title,
          kind: kind,
          updatedAt: session.updatedAt,
          pending: waiting,
          stopped: stopped,
        ),
      );
    }
    out.sort((a, b) {
      final byKind = a.kind.index.compareTo(b.kind.index);
      return byKind != 0 ? byKind : b.updatedAt.compareTo(a.updatedAt);
    });
    return out;
  }

  /// 「1 个等你 · 2 个在跑 · 1 个已中断 · 3 个跑完了」, empty parts left out.
  static String summary(List<DockTask> tasks, L10n l10n) {
    int count(DockTaskKind kind) => tasks.where((t) => t.kind == kind && !t.stopped).length;
    final waiting = count(DockTaskKind.waiting);
    final running = count(DockTaskKind.running);
    final stopped = tasks.where((t) => t.stopped).length;
    final finished = count(DockTaskKind.finished);
    return [
      if (waiting > 0) l10n.dockTasksWaiting(waiting),
      if (running > 0) l10n.dockTasksRunning(running),
      if (stopped > 0) l10n.dockTasksInterrupted(stopped),
      if (finished > 0) l10n.dockTasksFinished(finished),
    ].join(' · ');
  }
}

/// 「◫ 1 个等你 · 2 个在跑 ▰ ˄」; a tap on the bar opens the list, and with a
/// single conversation in it, goes straight there.
class TasksBar extends ConsumerStatefulWidget {
  const TasksBar({super.key, required this.tasks, this.initiallyOpen = false});

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
    final single = tasks.length == 1;
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
                        for (final task in tasks) DockTaskRow(key: ValueKey(task.sessionId), task: task, onTap: () => _go(task)),
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
    final waiting = tasks.where((t) => t.kind == DockTaskKind.waiting).length;
    final running = tasks.where((t) => t.kind == DockTaskKind.running && !t.stopped).length;
    final stopped = tasks.where((t) => t.stopped).length;
    final finished = tasks.where((t) => t.kind == DockTaskKind.finished).length;
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
          if (waiting > 0) segment(waiting, dsh.warning),
          if (running > 0) segment(running, colors.primary),
          if (stopped > 0) segment(stopped, dsh.warning),
          if (finished > 0) segment(finished, dsh.textTertiary),
        ],
      ),
    );
  }
}

/// One conversation: its marker, its title, and what it is doing.
class DockTaskRow extends StatelessWidget {
  const DockTaskRow({super.key, required this.task, required this.onTap});

  final DockTask task;
  final VoidCallback onTap;

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
    };
    return Semantics(
      button: true,
      label: '${task.title}, $state',
      excludeSemantics: true,
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
                    style: DockText.title(context),
                    maxLines: 1,
                    softWrap: false,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                const SizedBox(width: DockMetrics.trailingGap),
                Text(
                  state,
                  style: DockText.detail(
                    context,
                    color: task.kind == DockTaskKind.waiting || task.stopped ? dsh.warning : colors.onSurfaceVariant,
                  ),
                  maxLines: 1,
                  softWrap: false,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
