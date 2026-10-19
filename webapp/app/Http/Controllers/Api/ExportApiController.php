<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Support\ExportBuilder;
use App\Support\Settings;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/** Read-only data for the analysis (token with ability `analyze`). Same structure as the JSON export of the web page. */
class ExportApiController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        $request->validate(['from' => 'nullable|date', 'to' => 'nullable|date', 'days' => 'nullable|integer|between:1,3650']);
        $tz = Settings::tz();
        $to = $request->filled('to') ? CarbonImmutable::parse($request->query('to'), $tz)->endOfDay() : CarbonImmutable::now($tz)->endOfDay();
        $from = $request->filled('from')
            ? CarbonImmutable::parse($request->query('from'), $tz)->startOfDay()
            : $to->subDays((int) $request->query('days', 30) - 1)->startOfDay();

        $sessions = DB::table('sleep_sessions')->join('devices', 'devices.id', '=', 'sleep_sessions.device_id')
            ->whereBetween('started_at', [$from->timestamp, $to->timestamp])->orderBy('started_at')
            ->get(['sleep_sessions.*', 'devices.external_id as device']);
        $data = ExportBuilder::build($sessions, $from->timestamp, $to->timestamp);
        $data['config_history'] = DB::table('detector_configs')->orderBy('id')->get()->map(fn ($r) => [
            'version' => $r->id, 'created_at' => $r->created_at, 'note' => $r->note, 'config' => json_decode($r->config, true),
        ]);
        $data['range'] = ['from' => $from->toDateString(), 'to' => $to->toDateString(), 'tz' => $tz];

        return response()->json($data);
    }
}
