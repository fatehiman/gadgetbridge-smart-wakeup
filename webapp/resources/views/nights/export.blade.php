@extends('layouts.app')
@section('title', 'Export')
@section('content')
<div class="page-head">
    <h1 class="page-title"><svg class="ic"><use href="/assets/icons.svg#i-download"/></svg> Export</h1>
</div>
<form class="card" method="get" action="{{ route('export.download') }}">
    <div class="row">
        <div><label for="from">From</label><input id="from" type="date" name="from" value="{{ now($tz)->subDays(29)->toDateString() }}" required></div>
        <div><label for="to">To</label><input id="to" type="date" name="to" value="{{ now($tz)->toDateString() }}" required></div>
        <div><label for="format">Format</label><select id="format" name="format"><option value="csv">CSV</option><option value="json">JSON</option></select></div>
        <div><label for="split">Files</label><select id="split" name="split"><option value="combined">One combined file</option><option value="per_night">One file per night (zip)</option></select></div>
    </div>
    <p class="small muted">Nights are selected by their start time (days in {{ $tz }}). CSV always comes as a zip with one CSV per kind (sessions, samples, events).</p>
    <button class="btn primary"><svg class="ic"><use href="/assets/icons.svg#i-download"/></svg> Download</button>
</form>
<div class="card">
    <h2>Columns</h2>
    <p class="small muted">All times are UTC epoch: seconds for sessions and samples, <b>milliseconds</b> for events. JSON fields are exported as JSON text in CSV.</p>
    @foreach ($cols as $kind => $list)
        <p><b>{{ $kind }}.csv</b>: @foreach ($list as $c)<code>{{ $c }}</code> @endforeach</p>
    @endforeach
    <p class="small muted"><code>session_uuid</code> in samples is found at export time: the session whose start/end contains the sample time (empty if none).</p>
</div>
@endsection
