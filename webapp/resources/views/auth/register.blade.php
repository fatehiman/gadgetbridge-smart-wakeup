@extends('layouts.app')
@section('title', 'Create owner')
@section('content')
<div class="login-wrap card">
    <h1>Create the owner account</h1>
    <p class="small muted">This page works only once. After the first user exists, it is closed.</p>
    <form method="post" action="{{ route('register') }}">
        @csrf
        <p><label for="name">Name</label><input id="name" name="name" value="{{ old('name') }}" required></p>
        <p><label for="email">Email</label><input id="email" name="email" type="email" value="{{ old('email') }}" required>
            @error('email')<div class="errors">{{ $message }}</div>@enderror</p>
        <p><label for="password">Password (10+ characters)</label><input id="password" name="password" type="password" required>
            @error('password')<div class="errors">{{ $message }}</div>@enderror</p>
        <p><label for="pc">Repeat password</label><input id="pc" name="password_confirmation" type="password" required></p>
        <button class="btn primary" style="width:100%">Create account</button>
    </form>
</div>
@endsection
