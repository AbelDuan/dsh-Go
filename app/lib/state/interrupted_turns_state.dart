// Conversations whose last turn was closed because the engine stopped: the
// `turn/end` event carries `reason.kind == 'interrupted'`, so the work never
// finished, yet the session is idle — the running dot has gone out and nothing
// on screen says the turn died half way. The task bar counts these as
// unfinished and offers the way back, which is what 「继续」 in the chat does.
//
// Nothing is polled for its own sake: a pass reads only the recently active
// idle conversations (a handful), skips rows whose `updatedAt` it already read,
// and runs again when the list changes.

import 'dart:async';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/foundation.dart' show setEquals;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../notify/chat_alerts.dart' show outcomeFromEvents;
import 'connection_state.dart';
import 'sessions_state.dart';

/// How many of the most recently active idle conversations one pass reads.
const interruptedCandidates = 8;

/// How old a row may be and still be worth reading.
const interruptedWindow = Duration(days: 7);

/// How long an unchanged candidate list stands before it is read again, so a
/// host that was unreachable gets another chance without any row moving.
const interruptedRefreshGap = Duration(seconds: 45);

/// The conversations an engine restart interrupted, as session ids.
class InterruptedTurns extends Notifier<Set<String>> {
  /// What the last read of each conversation said, kept while its row's
  /// `updatedAt` stays put: a turn end moves it, anything else does not.
  final Map<String, ({DateTime updatedAt, bool interrupted})> _known = {};

  /// The candidate list the last pass ran over, so noise (activity stamps,
  /// titles) does not trigger another round of reads.
  String _lastPass = '';
  DateTime _lastAt = DateTime.fromMillisecondsSinceEpoch(0);

  /// Bumped by every pass; a stale pass drops its result.
  int _pass = 0;

  @override
  Set<String> build() {
    ref.listen(sessionsProvider, (_, _) => unawaited(_refresh()));
    ref.listen(activeConnectionProvider, (_, _) => unawaited(_refresh()));
    unawaited(_refresh());
    return const {};
  }

  Future<void> _refresh() async {
    final connection = ref.read(activeConnectionProvider).value;
    final sessions = ref.read(sessionsProvider).value;
    if (connection == null || sessions == null) {
      if (state.isNotEmpty) state = const {};
      return;
    }
    final now = DateTime.now();
    final candidates = [
      for (final session in sessions)
        if (!session.running &&
            !session.blank &&
            !session.isSubagent &&
            now.difference(session.updatedAt) < interruptedWindow)
          session,
    ].take(interruptedCandidates).toList(growable: false);
    final pass = candidates.map((s) => '${s.sessionId}@${s.updatedAt.millisecondsSinceEpoch}').join('|');
    if (pass == _lastPass && now.difference(_lastAt) < interruptedRefreshGap) return;
    _lastPass = pass;
    _lastAt = now;
    final token = ++_pass;
    final client = connection.client;
    final found = <String>{};
    for (final session in candidates) {
      final known = _known[session.sessionId];
      final bool interrupted;
      if (known != null && known.updatedAt == session.updatedAt) {
        interrupted = known.interrupted;
      } else {
        interrupted = await _read(client, session.sessionId);
        if (token != _pass || !ref.mounted) return;
        _known[session.sessionId] = (updatedAt: session.updatedAt, interrupted: interrupted);
      }
      if (interrupted) found.add(session.sessionId);
    }
    if (token != _pass || !ref.mounted) return;
    if (!setEquals(found, state)) state = found;
  }

  /// Whether [sessionId]'s last turn ended because the engine stopped.
  Future<bool> _read(DshClient client, String sessionId) async {
    try {
      final projections = await client.projections(sessionId).timeout(const Duration(seconds: 4));
      if (projections == null || projections.asOfSeq < 0) return false;
      // One message back from the cut carries the last reply or prompt; the
      // `turn/end` after it is what says how it ended.
      final page = await client
          .history(sessionId, throughSeq: projections.asOfSeq, maxMessages: 1)
          .timeout(const Duration(seconds: 4));
      final outcome = outcomeFromEvents(page.events);
      return outcome?.reason.kind == 'interrupted';
    } catch (_) {
      // A host that cannot answer (closed, too old, permission) has no say here.
      return false;
    }
  }
}

final interruptedTurnsProvider = NotifierProvider<InterruptedTurns, Set<String>>(InterruptedTurns.new);
