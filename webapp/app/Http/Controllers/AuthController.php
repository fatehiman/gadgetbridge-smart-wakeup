<?php

namespace App\Http\Controllers;

use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Auth;

class AuthController extends Controller
{
    public function showLogin()
    {
        return view('auth.login', ['canRegister' => User::count() === 0]);
    }

    public function login(Request $r)
    {
        $c = $r->validate(['email' => 'required|email', 'password' => 'required']);
        if (! Auth::attempt($c, true)) {
            return back()->withErrors(['email' => 'Wrong email or password.'])->onlyInput('email');
        }
        $r->session()->regenerate();

        return redirect()->intended('/');
    }

    public function showRegister()
    {
        abort_if(User::count() > 0, 404);

        return view('auth.register');
    }

    /** Only the first user can register. After that the page is gone. */
    public function register(Request $r)
    {
        abort_if(User::count() > 0, 404);
        $c = $r->validate(['name' => 'required|string|max:100', 'email' => 'required|email', 'password' => 'required|min:10|confirmed']);
        $u = User::create($c);
        Auth::login($u, true);
        $r->session()->regenerate();

        return redirect('/');
    }

    public function logout(Request $r)
    {
        Auth::logout();
        $r->session()->invalidate();
        $r->session()->regenerateToken();

        return redirect('/login');
    }
}
