// Dados sintéticos; nenhuma autorização ou credencial pertence ao workbook.
export const planosCarga = {
  Concluir: {
    servico: 'front-office',
    modo: 'ABERTO',
    estagios: [{ duracaoMs: 1200, rps: 5, vus: 0 }],
    aquecimentoMs: 200,
    concorrencia: 2,
    maxChamadas: 10,
    timeoutMs: 1000,
    maxRespostaBytes: 4096,
  },
  Cancelar: {
    servico: 'front-office',
    modo: 'FECHADO',
    estagios: [{ duracaoMs: 5000, rps: 5, vus: 1 }],
    aquecimentoMs: 0,
    concorrencia: 1,
    maxChamadas: 30,
    timeoutMs: 1000,
    maxRespostaBytes: 4096,
  },
};
