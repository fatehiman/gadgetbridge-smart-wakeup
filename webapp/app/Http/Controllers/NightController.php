<?php

namespace App\Http\Controllers;

use App\Support\Night;
use App\Support\Settings;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class NightController extends Controller
{
    public function index(Request $r)
    {
        $tz = Settings::tz();
        $q = DB::table('sleep_sessions')->orderByDesc('started_at');
        if ($r->filled('from')) {
            $q->where('started_at', '>=', CarbonImmutable::parse($r->query('from'), $tz)->startOfDay()->timestamp);
        }
        if ($r->filled('to')) {
            $q->where('started_at', '<=', CarbonImmutable::parse($r->query('to'), $tz)->endOfDay()->timestamp);
        }
        $page = $q->paginate(30)->withQueryString();

        return view('nights.index', ['page' => $page, 'rows' => Night::summaries($page->items()), 'tz' => $tz]);
    }

    public function show(int $session)
    {
        $s = DB::table('sleep_sessions')->find($session);
        abort_unless($s, 404);
        $tz = $s->timezone ?: Settings::tz();
        $events = DB::table('events')->where('session_id', $s->id)->orderBy('ts')->get();
        $chart = [
            'tz' => $tz,
            'start' => $s->started_at, 'end' => $s->ended_at,
            'window' => [$s->window_start, $s->deadline],
            'points' => Night::chartSamples($s),
            'events' => $events->whereIn('type', ['detector_evaluated', 'wake_triggered', 'wake_acknowledged', 'session_started', 'session_stopped'])
                ->map(fn ($e) => ['ts' => $e->ts / 1000, 'type' => $e->type, 'decision' => (json_decode($e->payload ?? 'null', true) ?: [])['decision'] ?? null])
                ->values(),
        ];
        $summary = Night::summaries([$s])[0];

        return view('nights.show', compact('s', 'tz', 'events', 'chart', 'summary'));
    }

    public function update(Request $r, int $session)
    {
        abort_unless(DB::table('sleep_sessions')->where('id', $session)->exists(), 404);
        $c = $r->validate(['morning_rating' => 'nullable|integer|between:1,5', 'notes' => 'nullable|string|max:5000']);
        DB::table('sleep_sessions')->where('id', $session)->update([
            'morning_rating' => $c['morning_rating'] ?? null, 'notes' => $c['notes'] ?? null, 'updated_at' => now(),
        ]);

        return back()->with('ok', 'Saved.');
    }
}
