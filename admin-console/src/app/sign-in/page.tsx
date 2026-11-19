import { SignInForm } from "./SignInForm";

export const metadata = { title: "Sign in · Beavent admin" };

export default function SignInPage() {
  return (
    <main className="mx-auto flex w-full max-w-sm flex-1 flex-col justify-center gap-6 px-6 py-16">
      <div>
        <h1 className="text-2xl font-semibold">Beavent admin</h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          Platform staff only. You need a staff role and an authenticator app.
        </p>
      </div>
      <SignInForm />
    </main>
  );
}
