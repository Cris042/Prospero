import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { setTimeout as delay } from "node:timers/promises";
import { runWorkbook } from "../../frontend/runner/index.mjs";
import { planosCarga } from "../../frontend/runner/exemplos/carga-planos.mjs";

const root = fileURLToPath(new URL("../../", import.meta.url));
const workbook = fileURLToPath(
  new URL("../../frontend/runner/exemplos/carga.xlsx", import.meta.url),
);
const execute = promisify(execFile);
const terminal = new Set(["CONCLUIDA", "CANCELADA", "FALHA"]);

// O hook registra só IDs para cancelar via API mesmo se uma asserção XLSX falhar.
export async function runCargaSmoke({
  origin,
  controlToken,
  authorizationForPlan,
  scenarios = ["Concluir", "Cancelar", "Rejeitar"],
}) {
  const base = new URL(origin);
  if (
    !["http:", "https:"].includes(base.protocol) ||
    base.username ||
    base.password ||
    base.pathname !== "/" ||
    base.search ||
    base.hash
  )
    throw new Error("ORIGEM_INVALIDA");
  const originalFetch = globalThis.fetch;
  const reports = [];
  for (const scenario of scenarios) {
    if (!["Concluir", "Cancelar", "Rejeitar"].includes(scenario))
      throw new Error("CENARIO_INVALIDO");
    const authorization =
      scenario === "Rejeitar"
        ? undefined
        : await authorizationForPlan(planosCarga[scenario]);
    const headers = { authorization: `Bearer ${controlToken}` };
    if (authorization) headers["x-prospero-preflight"] = authorization;
    const identities = new Set();
    const control = async (id, cancel = false) => {
      const response = await originalFetch(
        new URL(
          `/api/v1/execucoes-carga/${id}${cancel ? "/cancelamentos" : ""}`,
          base,
        ),
        {
          method: cancel ? "POST" : "GET",
          headers: { authorization: headers.authorization },
          redirect: "error",
          signal: AbortSignal.timeout(5000),
        },
      );
      if (!response.ok) throw new Error("LIMPEZA_API_RECUSADA");
      return response.json();
    };
    try {
      globalThis.fetch = async (input, init) => {
        const response = await originalFetch(input, init);
        const url = new URL(input instanceof Request ? input.url : input);
        if (
          url.origin === base.origin &&
          url.pathname === "/api/v1/execucoes-carga" &&
          init?.method === "POST" &&
          response.status === 202
        ) {
          const accepted = await response.clone().json();
          if (/^[0-9a-f]{32}$/.test(accepted.id)) identities.add(accepted.id);
        }
        return response;
      };
      const report = await runWorkbook(
        workbook,
        { servicos: { carga: { origem: base.origin, headers } } },
        { scenario },
      );
      reports.push(report);
      if (!report.sucesso) throw new Error(`XLSX_FALHOU_${scenario}`);
    } finally {
      globalThis.fetch = originalFetch;
      for (const id of identities) {
        let report = await control(id, true);
        const deadline = performance.now() + 4000;
        while (!terminal.has(report.estado) && performance.now() < deadline) {
          await delay(100);
          report = await control(id);
        }
        if (!terminal.has(report.estado) || report.contagens.emVoo !== 0)
          throw new Error("LIMPEZA_NAO_TERMINOU");
      }
    }
    // O controlador exige intervalo entre admissões; não é prova de conclusão.
    if (scenario !== "Rejeitar") await delay(1100);
  }
  return reports;
}

async function main() {
  const privateDir = await mkdtemp(join(tmpdir(), "prospero-carga-smoke-"));
  try {
    const tokenPath = join(privateDir, "token.txt");
    await execute(
      "python3",
      [join(root, "scripts/carga.py"), "token", "--saida", tokenPath],
      { cwd: root },
    );
    const controlToken = (await readFile(tokenPath, "utf8")).trim();
    const reports = await runCargaSmoke({
      origin: "http://127.0.0.1:8090",
      controlToken,
      authorizationForPlan: async (plan) => {
        const planPath = join(privateDir, "plano.json");
        const requestPath = join(privateDir, "preflight.json");
        await writeFile(planPath, JSON.stringify(plan), { mode: 0o600 });
        await execute(
          "python3",
          [
            join(root, "scripts/carga.py"),
            "preflight",
            "--plano",
            planPath,
            "--saida",
            requestPath,
          ],
          { cwd: root },
        );
        return JSON.parse(await readFile(requestPath, "utf8")).autorizacao;
      },
    });
    console.log(
      JSON.stringify({
        tipo: "xlsx-controle-carga-tecnica",
        sucesso: true,
        cenarios: reports.map((r) => r.cenarios[0].cenario),
      }),
    );
  } finally {
    await rm(privateDir, { recursive: true, force: true });
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  main().catch(() => {
    console.error(
      "Smoke XLSX recusado; conferir serviço, preflight e relatório, sem credenciais nos logs.",
    );
    process.exitCode = 1;
  });
}
