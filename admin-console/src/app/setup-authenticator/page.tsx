import { EnrollForm } from "./EnrollForm";

export const metadata = { title: "Authenticator app · Beavent admin" };

export default function SetupAuthenticatorPage() {
  return (
    <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center gap-6 px-6 py-16">
      <div>
        <h1 className="text-2xl font-semibold">Set up an authenticator app</h1>
        <p className="text-sm text-zinc-600 dark:text-zinc-400">
          The admin console needs a second factor. After set-up you will sign in again with your
          phone code and a code from the app.
        </p>
      </div>
      <EnrollForm />
    </main>
  );
}
