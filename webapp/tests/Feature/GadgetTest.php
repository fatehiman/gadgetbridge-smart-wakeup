<?php

namespace Tests\Feature;

use App\Models\User;
use App\Support\Cleanup;
use App\Support\Settings;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Tests\TestCase;

class GadgetTest extends TestCase
{
    use RefreshDatabase;

    private string $token;

    protected function setUp(): void
    {
        parent::setUp();
        $this->token = 'sw_testtoken';
        DB::table('api_tokens')->insert(['name' => 't', 'token_hash' => hash('sha256', $this->token), 'ability' => 'ingest',
            'created_at' => now(), 'updated_at' => now()]);
    }

    private function batch(array $over = []): array
    {
        $t = time() - 3600;

        return array_merge([
            'device' => ['external_id' => 'abc', 'name' => 'CMF Watch Pro 2', 'model' => '3BAD'],
            'sessions' => [['uuid' => 'u-1', 'started_at' => $t - 3600, 'ended_at' => $t + 3600, 'mode' => 'smart_wake', 'timezone' => 'Europe/Helsinki']],
            'samples' => [['ts' => $t, 'heart_rate' => 58, 'steps' => 0, 'intensity' => 3, 'extra' => ['x' => 1]], ['ts' => $t + 60, 'heart_rate' => 60]],
            'events' => [['session_uuid' => 'u-1', 'ts' => $t * 1000, 'type' => 'detector_evaluated', 'payload' => ['decision' => 'wait']]],
        ], $over);
    }

    private function ingest(array $b, ?string $token = null)
    {
        return $this->withToken($token ?? $this->token)->postJson('/api/v1/ingest', $b);
    }

    public function test_api_requires_token(): void
    {
        $this->getJson('/api/v1/ping')->assertStatus(401);
        $this->withToken('wrong')->getJson('/api/v1/ping')->assertStatus(401);
        $this->withToken($this->token)->getJson('/api/v1/ping')->assertOk()->assertJson(['ok' => true]);
    }

    public function test_revoked_token_is_rejected(): void
    {
        DB::table('api_tokens')->update(['revoked_at' => now()]);
        $this->withToken($this->token)->getJson('/api/v1/ping')->assertStatus(401);
    }

    public function test_ingest_is_idempotent(): void
    {
        $b = $this->batch();
        $this->ingest($b)->assertOk()->assertJson(['accepted' => ['sessions' => 1, 'samples' => 2, 'events' => 1], 'duplicates' => 0]);
        $this->ingest($b)->assertOk()->assertJson(['duplicates' => 4]);
        $this->assertSame(2, DB::table('samples')->count());
        $this->assertSame(1, DB::table('events')->count());
        $this->assertSame(1, DB::table('sleep_sessions')->count());
        $this->assertSame(1, DB::table('devices')->count());
    }

    public function test_late_backfilled_samples_are_added(): void
    {
        $b = $this->batch();
        $first = $b['samples'][0]['ts'];
        $this->ingest($b)->assertOk();
        $this->ingest(['device' => ['external_id' => 'abc'], 'samples' => [['ts' => $first - 3600, 'heart_rate' => 50], ['ts' => $first, 'heart_rate' => 77]]])->assertOk();
        $this->assertSame(3, DB::table('samples')->count());
        $this->assertSame(77, (int) DB::table('samples')->where('ts', $first)->value('heart_rate'));
    }

    public function test_validation_errors(): void
    {
        $this->ingest(['device' => ['external_id' => 'abc'], 'samples' => [['ts' => 5, 'heart_rate' => 58]]])->assertStatus(422);
        $this->ingest(['samples' => []])->assertStatus(422);
        $many = array_map(fn ($i) => ['ts' => 1700000000 + $i], range(1, 5001));
        $this->ingest(['device' => ['external_id' => 'abc'], 'samples' => $many])->assertStatus(422);
        $this->assertSame(0, DB::table('samples')->count());
        $this->assertGreaterThan(0, DB::table('ingest_logs')->where('status', 422)->count());
    }

    public function test_events_for_unknown_session_are_skipped(): void
    {
        $b = $this->batch(['sessions' => []]);
        $this->ingest($b)->assertOk()->assertJson(['accepted' => ['events' => 0]]);
    }

    public function test_morning_rating_event_updates_session(): void
    {
        $b = $this->batch();
        $b['events'][] = ['session_uuid' => 'u-1', 'ts' => (time() - 100) * 1000, 'type' => 'morning_rating', 'payload' => ['rating' => 4, 'note' => 'ok']];
        $this->ingest($b)->assertOk();
        $this->assertSame(4, (int) DB::table('sleep_sessions')->value('morning_rating'));
        $this->assertSame('ok', DB::table('sleep_sessions')->value('notes'));
    }

    public function test_web_requires_login_and_register_closes(): void
    {
        $this->get('/')->assertRedirect('/login');
        $this->get('/register')->assertOk();
        $this->post('/register', ['name' => 'Me', 'email' => 'me@example.com', 'password' => 'longpassword1', 'password_confirmation' => 'longpassword1'])->assertRedirect('/');
        $this->post('/logout');
        $this->get('/register')->assertNotFound();
    }

    public function test_pages_export_and_cleanup(): void
    {
        $this->ingest($this->batch())->assertOk();
        $user = User::factory()->create();
        $this->actingAs($user);
        $id = DB::table('sleep_sessions')->value('id');
        $this->get('/')->assertOk()->assertSee('smart wake');
        $this->get("/nights/$id")->assertOk()->assertSee('detector_evaluated');
        $this->get('/trends')->assertOk();
        $this->get('/settings')->assertOk();
        $this->post("/nights/$id", ['morning_rating' => 3, 'notes' => 'hello'])->assertRedirect();
        $this->assertSame('hello', DB::table('sleep_sessions')->value('notes'));

        $q = ['from' => now()->subDay()->toDateString(), 'to' => now()->toDateString()];
        $json = $this->get('/export/download?'.http_build_query($q + ['format' => 'json', 'split' => 'combined']))->assertOk();
        $data = json_decode($json->getContent(), true);
        $this->assertCount(1, $data['sessions']);
        $this->assertCount(2, $data['samples']);
        $this->assertSame('u-1', $data['samples'][0]['session_uuid']);
        $this->get('/export/download?'.http_build_query($q + ['format' => 'csv', 'split' => 'per_night']))->assertOk();
    }

    public function test_cleanup_deletes_old_data_and_logs(): void
    {
        $old = time() - 100 * 86400;
        $this->ingest(['device' => ['external_id' => 'abc'], 'sessions' => [['uuid' => 'old', 'started_at' => $old, 'ended_at' => $old + 100]],
            'samples' => [['ts' => $old, 'heart_rate' => 50], ['ts' => time() - 100, 'heart_rate' => 60]],
            'events' => [['session_uuid' => 'old', 'ts' => $old * 1000, 'type' => 'x']]])->assertOk();
        DB::table('ingest_logs')->insert(['created_at' => now()->subDays(100), 'status' => 200]);

        $r = Cleanup::run(90, 90);
        $this->assertSame(['samples' => 1, 'events' => 1, 'sessions' => 1], ['samples' => $r['samples'], 'events' => $r['events'], 'sessions' => $r['sessions']]);
        $this->assertSame(1, $r['ingest_logs']);
        $this->assertSame(1, DB::table('samples')->count());

        // 0 = keep forever; default is 90 days
        $this->assertSame('90', Settings::get('retention_days_data'));
        $this->assertSame(0, Cleanup::run(0, 0)['samples']);
    }

    public function test_settings_save_retention(): void
    {
        $this->actingAs(User::factory()->create());
        $this->post('/settings', ['timezone' => 'Europe/Helsinki', 'retention_days_data' => 30, 'retention_days_logs' => 7])->assertRedirect();
        $this->assertSame('30', Settings::get('retention_days_data'));
        $this->assertSame('7', Settings::get('retention_days_logs'));
        $this->post('/settings', ['timezone' => 'Nope/Zone', 'retention_days_data' => 30, 'retention_days_logs' => 7])->assertSessionHasErrors('timezone');
    }

    private function analyzeToken(): string
    {
        $t = 'sw_analyze_token';
        DB::table('api_tokens')->insert(['name' => 'a', 'token_hash' => hash('sha256', $t), 'ability' => 'analyze', 'created_at' => now(), 'updated_at' => now()]);

        return $t;
    }

    public function test_analyze_token_reads_export_and_writes_config_but_cannot_ingest(): void
    {
        $this->ingest($this->batch())->assertOk();
        $a = $this->analyzeToken();
        $this->withToken($a)->postJson('/api/v1/ingest', $this->batch())->assertStatus(401);
        $r = $this->withToken($a)->getJson('/api/v1/export?days=3')->assertOk();
        $this->assertCount(1, $r->json('sessions'));
        $this->assertCount(2, $r->json('samples'));
        $this->assertSame('u-1', $r->json('samples.0.session_uuid'));
        $this->withToken($this->token)->getJson('/api/v1/export')->assertStatus(401); // phone token cannot read everything
    }

    public function test_detector_config_roundtrip_and_validation(): void
    {
        $a = $this->analyzeToken();
        $this->withToken($this->token)->getJson('/api/v1/config')->assertOk()->assertJson(['version' => 0, 'config' => null]);
        $cfg = ['detector' => 'scored_v1', 'params' => ['threshold' => 1.5, 'weights' => ['hr_delta' => 0.2]]];
        $this->withToken($this->token)->putJson('/api/v1/config', ['config' => $cfg])->assertStatus(401); // phone cannot write
        $this->withToken($a)->putJson('/api/v1/config', ['config' => ['detector' => 'evil']])->assertStatus(422);
        $this->withToken($a)->putJson('/api/v1/config', ['config' => $cfg, 'note' => 'first'])->assertOk()->assertJson(['version' => 1]);
        $this->withToken($this->token)->getJson('/api/v1/config')->assertOk()->assertJson(['version' => 1, 'config' => $cfg, 'note' => 'first']);
        $this->withToken($a)->getJson('/api/v1/config/history')->assertOk()->assertJsonCount(1, 'history');
    }
}
