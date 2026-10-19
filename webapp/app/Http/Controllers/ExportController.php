<?php

namespace App\Http\Controllers;

use App\Support\ExportBuilder;
use App\Support\Settings;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class ExportController extends Controller
{
    private const COLS = [
        'sessions' => ['uuid', 'device', 'started_at', 'ended_at', 'mode', 'target_time', 'tolerance_min', 'window_start', 'deadline',
            'detector_id', 'detector_params', 'timezone', 'wake_reason', 'morning_rating', 'notes'],
        'samples' => ['session_uuid', 'device', 'ts', 'heart_rate', 'steps', 'intensity', 'movement', 'spo2', 'sleep_stage', 'extra'],
        'events' => ['session_uuid', 'device', 'ts', 'type', 'payload'],
    ];

    public function index()
    {
        return view('nights.export', ['cols' => self::COLS, 'tz' => Settings::tz()]);
    }

    public function download(Request $r)
    {
        $c = $r->validate([
            'from' => 'required|date', 'to' => 'required|date|after_or_equal:from',
            'format' => 'required|in:csv,json', 'split' => 'required|in:combined,per_night',
        ]);
        $tz = Settings::tz();
        $from = CarbonImmutable::parse($c['from'], $tz)->startOfDay()->timestamp;
        $to = CarbonImmutable::parse($c['to'], $tz)->endOfDay()->timestamp;
        $sessions = DB::table('sleep_sessions')->join('devices', 'devices.id', '=', 'sleep_sessions.device_id')
            ->whereBetween('started_at', [$from, $to])->orderBy('started_at')
            ->get(['sleep_sessions.*', 'devices.external_id as device']);
        $name = "gadget-{$c['from']}_{$c['to']}";

        if ($c['split'] === 'combined') {
            $parts = ExportBuilder::build($sessions, $from, $to);
            if ($c['format'] === 'json') {
                return response(json_encode($parts, JSON_UNESCAPED_SLASHES), 200, [
                    'Content-Type' => 'application/json',
                    'Content-Disposition' => "attachment; filename=\"$name.json\"",
                ]);
            }

            return $this->zip($name, $this->csvFiles($parts, ''));
        }

        // one file set per night: samples between started_at and ended_at
        $files = [];
        foreach ($sessions as $s) {
            $label = CarbonImmutable::createFromTimestamp($s->started_at, $s->timezone ?: $tz)->format('Y-m-d_Hi').'_'.substr($s->uuid, 0, 8);
            $parts = ExportBuilder::build(collect([$s]), $s->started_at, $s->ended_at ?? $s->started_at + 86400);
            if ($c['format'] === 'json') {
                $files["$label.json"] = json_encode($parts, JSON_UNESCAPED_SLASHES);
            } else {
                $files += $this->csvFiles($parts, "$label-");
            }
        }

        return $this->zip($name, $files);
    }

    private function csvFiles(array $parts, string $prefix): array
    {
        $files = [];
        foreach (self::COLS as $kind => $cols) {
            $h = fopen('php://temp', 'w+');
            fputcsv($h, $cols);
            foreach ($parts[$kind] as $row) {
                fputcsv($h, array_map(fn ($c) => is_array($row[$c]) || is_object($row[$c]) ? json_encode($row[$c]) : $row[$c], $cols));
            }
            rewind($h);
            $files["$prefix$kind.csv"] = stream_get_contents($h);
            fclose($h);
        }

        return $files;
    }

    private function zip(string $name, array $files)
    {
        $tmp = tempnam(sys_get_temp_dir(), 'wz');
        $z = new \ZipArchive;
        $z->open($tmp, \ZipArchive::OVERWRITE);
        foreach ($files as $n => $content) {
            $z->addFromString($n, $content);
        }
        $z->close();

        return response()->download($tmp, "$name.zip")->deleteFileAfterSend(true);
    }
}
