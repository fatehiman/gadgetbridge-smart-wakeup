<?php

namespace App\Http\Controllers;

use App\Http\Controllers\Api\ConfigController;
use App\Support\Cleanup;
use App\Support\Settings;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class SettingsController extends Controller
{
    public function index()
    {
        return view('nights.settings', [
            'tz' => Settings::tz(),
            'zones' => \DateTimeZone::listIdentifiers(),
            'dataDays' => Settings::get('retention_days_data'),
            'logDays' => Settings::get('retention_days_logs'),
            'tokens' => DB::table('api_tokens')->orderByDesc('id')->get(),
            'devices' => DB::table('devices')->orderBy('name')->get(),
            'logs' => DB::table('ingest_logs')->orderByDesc('id')->limit(15)->get(),
            'counts' => [
                'samples' => DB::table('samples')->count(),
                'events' => DB::table('events')->count(),
                'sessions' => DB::table('sleep_sessions')->count(),
                'logs' => DB::table('ingest_logs')->count(),
            ],
            'newToken' => session('new_token'),
            'configs' => DB::table('detector_configs')->orderByDesc('id')->limit(10)->get(),
        ]);
    }

    public function save(Request $r)
    {
        $c = $r->validate([
            'timezone' => ['required', 'string', function ($attr, $value, $fail) {
                if (! in_array($value, \DateTimeZone::listIdentifiers(), true)) {
                    $fail('Unknown time zone.');
                }
            }],
            'retention_days_data' => 'required|integer|between:0,36500',
            'retention_days_logs' => 'required|integer|between:0,36500',
        ]);
        foreach ($c as $k => $v) {
            Settings::set($k, (string) $v);
        }

        return back()->with('ok', 'Settings saved.');
    }

    public function cleanup()
    {
        $r = Cleanup::run();

        return back()->with('ok', sprintf(
            'Deleted %d samples, %d events, %d sessions, %d ingest logs, %d log files.',
            $r['samples'], $r['events'], $r['sessions'], $r['ingest_logs'], $r['log_files']
        ));
    }

    public function createToken(Request $r)
    {
        $c = $r->validate(['name' => 'required|string|max:100', 'ability' => 'required|in:ingest,analyze']);
        $plain = 'sw_'.Str::random(48);
        DB::table('api_tokens')->insert([
            'name' => $c['name'], 'token_hash' => hash('sha256', $plain), 'ability' => $c['ability'],
            'created_at' => now(), 'updated_at' => now(),
        ]);

        return back()->with('new_token', $plain);
    }

    /** Manual detector config (the analysis normally pushes it through the API). */
    public function saveConfig(Request $r)
    {
        $r->validate(['config_json' => 'required|string|max:20000', 'note' => 'nullable|string|max:1000']);
        $cfg = json_decode($r->input('config_json'), true);
        if ($err = AppHttp\Controllers\Api\ConfigController::validateConfig($cfg)) {
            return back()->withErrors(['config_json' => $err])->withInput();
        }
        DB::table('detector_configs')->insert(['note' => $r->input('note') ?: null, 'config' => json_encode($cfg)]);

        return back()->with('ok', 'Detector config saved. The phone downloads it at the next sync.');
    }

    public function revokeToken(int $id)
    {
        DB::table('api_tokens')->where('id', $id)->whereNull('revoked_at')->update(['revoked_at' => now()]);

        return back()->with('ok', 'Token revoked.');
    }
}
