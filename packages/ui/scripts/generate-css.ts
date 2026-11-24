// Writes packages/ui/tokens.css from the tokens (run: npm run generate -w @beavent/ui).
import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { tokenStylesheet } from "../src/index.ts";

const target = fileURLToPath(new URL("../tokens.css", import.meta.url));
writeFileSync(target, tokenStylesheet());
console.log(`wrote ${target}`);
