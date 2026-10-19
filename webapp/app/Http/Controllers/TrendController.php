<?php

namespace App\Http\Controllers;

use App\Support\Night;
use App\Support\Settings;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class TrendController extends Controller
{
    public function index(Request $r)
    {
        $tz = Settings::tz();
        $period = $r->query('period', 'month');
        $to = $r->filled('to') ? CarbonImmutable::parse($r->query('to'), $tz)->endOfDay() : CarbonImmutable::now($tz)->endOfDay();
        $from = match ($period) {
            'week' => $to->subDays(6)->startOfDay(),
            'custom' => CarbonImmutable::parse($r->query('from', $to->subDays(29)->toDateString()), $tz)->startOfDay(),
            default => $to->subDays(29)->startOfDay(),
        };
        $sessions = DB::table('sleep_sessions')->whereBetween('started_at', [$from->timestamp, $to->timestamp])
            ->orderBy('started_at')->get();
        $rows = Night::summaries($sessions);

        $points = [];
        $reasons = [];
        foreach ($rows as $row) {
            $s = $row->s;
            $stz = $s->timezone ?: $tz;
            $wake = $row->wake_ts ? CarbonImmutable::createFromTimestamp($row->wake_ts, $stz) : null;
            $target = $s->target_time ? CarbonImmutable::createFromTimestamp($s->target_time, $stz) : null;
            $points[] = [
                'label' => CarbonImmutable::createFromTimestamp($s->started_at, $stz)->format('M j'),
                'wake_min' => $wake ? $wake->hour * 60 + $wake->minute : null,
                'target_min' => $target ? $target->hour * 60 + $target->minute : null,
                'avg_hr' => $row->avg_hr,
                'hours' => $row->duration ? round($row->duration / 3600, 2) : null,
            ];
            $k = $row->wake_reason ?: 'unknown';
            $reasons[$k] = ($reasons[$k] ?? 0) + 1;
        }

        return view('nights.trends', [
            'period' => $period, 'from' => $from->toDateString(), 'to' => $to->toDateString(),
            'data' => ['points' => $points, 'reasons' => $reasons], 'count' => count($rows),
        ]);
    }
}
