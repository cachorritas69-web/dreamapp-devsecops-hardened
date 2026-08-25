import { initializeApp } from "firebase/app";
import { GoogleAuthProvider, getAuth, signInWithPopup } from "firebase/auth";

const firebaseApp = initializeApp({
  apiKey:
    import.meta.env.VITE_FIREBASE_API_KEY ||
    "AIzaSyAj8yczN0TFZjDVbAAwvdCBZQcU74vGPPw",
  authDomain:
    import.meta.env.VITE_FIREBASE_AUTH_DOMAIN ||
    "dreamapp-c767e.firebaseapp.com",
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID || "dreamapp-c767e",
});

const auth = getAuth(firebaseApp);
const googleProvider = new GoogleAuthProvider();
googleProvider.setCustomParameters({ prompt: "select_account" });

export async function getGoogleIdToken(): Promise<string> {
  const credential = await signInWithPopup(auth, googleProvider);
  return credential.user.getIdToken(true);
}
