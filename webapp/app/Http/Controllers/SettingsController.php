<?php

namespace App\Http\Controllers;

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
        $c = $r->validate(['name' => 'required|string|max:100']);
        $plain = 'sw_'.Str::random(48);
        DB::table('api_tokens')->insert([
            'name' => $c['name'], 'token_hash' => hash('sha256', $plain), 'ability' => 'ingest',
            'created_at' => now(), 'updated_at' => now(),
        ]);

        return back()->with('new_token', $plain);
    }

    public function revokeToken(int $id)
    {
        DB::table('api_tokens')->where('id', $id)->whereNull('revoked_at')->update(['revoked_at' => now()]);

        return back()->with('ok', 'Token revoked.');
    }
}
