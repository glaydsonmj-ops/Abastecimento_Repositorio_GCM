/**
 * Controle de Abastecimento GCM Serviços - Google Apps Script
 *
 * Instalação (uma única vez):
 * 1. Abra a planilha "Controle de Abastecimento GCM Serviços".
 * 2. Menu Extensões > Apps Script. Apague o conteúdo e cole este arquivo.
 * 3. Implantar > Nova implantação > Tipo "App da Web".
 *    Executar como: Eu. Quem pode acessar: Qualquer pessoa.
 * 4. A URL gerada fica embutida no aplicativo Android.
 *
 * Acesso dos técnicos: nome + senha de 4 números (aba Técnicos, coluna E).
 * No primeiro acesso, o próprio técnico cria a senha; para trocá-la, apague a célula.
 * Versão para iPhone (navegador): <URL do app>?pagina=tecnico
 * A chave antiga (Configurações!B8) continua aceita só para versões anteriores do app.
 */

const ABA = {
  VEICULOS: 'Veículos',
  TECNICOS: 'Técnicos',
  LEITURAS: 'Leituras',
  ABASTECIMENTOS: 'Abastecimentos',
  ALERTAS: 'Alertas',
  CONFIG: 'Configurações',
  CHECAGEM: 'Checagem diária',
  OLEO: 'Trocas de óleo',
  ITENS: 'Itens dos check-lists',
  CHECKLISTS: 'Check-lists',
};
const CHECKLISTS = ['Segurança do veículo', 'Ferramental e EPI'];
const TZ = 'America/Sao_Paulo';
/* Colunas da aba Veículos (base 0). */
const V = { PLACA: 0, MODELO: 1, ANO: 2, COR: 3, KM_COUBE: 4, KM_ULT_ABAST: 5, KM_ATUAL: 6, KM_RODADOS: 7,
            KM_RESTANTES: 8, LITROS_REST: 9, SITUACAO: 10, OLEO_KM: 11, OLEO_DATA: 12, SEGURO: 13, MANUTENCAO: 14, OLEO_AGENDADA: 15 };
const PASTA_FOTOS = 'Fotos Abastecimento GCM';

/**
 * GET: devolve veículos, técnicos e configurações para o aplicativo.
 * Com tecnico e placa, inclui o resumo do dia ("hoje"); com relatorio=1, inclui o relatório do técnico.
 * Com pagina=frota, abre a página de gestão de frota (chave da gestão em Configurações!B12).
 */
function doGet(e) {
  const p = e.parameter || {};
  if (p.pagina === 'frota') return paginaFrota_(p.chave);
  if (p.pagina === 'tecnico' || p.pagina === 'app') return paginaTecnico_();
  try {
    return json_(consultar_(p));
  } catch (err) {
    return json_({ ok: false, erro: String(err) });
  }
}

/** Consulta usada pelo app Android (GET) e pela versão web (iPhone). */
function consultar_(p) {
  if (p.acao === 'tecnicos') return listaTecnicos_();
  if (!autorizado_(p)) return { ok: false, erro: ERRO_LOGIN };
  const ss = SpreadsheetApp.getActive();
  const cfg = parametros_();
  const veiculos = linhas_(ss.getSheetByName(ABA.VEICULOS)).filter(r => r[V.PLACA]).map(r => veiculoJson_(r, cfg));
  const tecnicos = linhas_(ss.getSheetByName(ABA.TECNICOS))
    .filter(r => r[0] && String(r[3]).toLowerCase() !== 'não')
    .map(r => ({ nome: r[0], email: r[1], placa: r[2] }));
  const config = { consumoKmL: cfg.consumo, antecedenciaKm: cfg.antecedencia, valorPadrao: cfg.valorPadrao,
                   lembreteInicio: cfg.lembreteInicio, lembreteFim: cfg.lembreteFim };
  const tecnico = p.tecnico || '';
  const placa = p.placa || '';
  const resposta = { ok: true, veiculos, tecnicos, config };
  if (tecnico && placa) {
    resposta.hoje = resumoDia_(tecnico, placa, new Date());
    resposta.checklists = situacaoChecklists_(tecnico, new Date());
  }
  if (tecnico && placa && p.relatorio === '1') resposta.relatorio = relatorio_(tecnico, placa, new Date());
  return resposta;
}

/** Parâmetros da aba Configurações. */
function parametros_() {
  const v = config_().getRange('B2:B12').getValues().map(l => l[0]);
  const hora = x => x instanceof Date ? Utilities.formatDate(x, TZ, 'HH:mm') : String(x || '');
  return {
    consumo: Number(v[0]) || 10, antecedencia: Number(v[1]) || 20, email: String(v[2] || '').trim(),
    valorPadrao: Number(v[3]) || 180, lembreteInicio: hora(v[4]) || '08:00', lembreteFim: hora(v[5]) || '17:00',
    oleoIntervalo: Number(v[7]) || 5000, oleoAviso: Number(v[8]) || 500, seguroAviso: Number(v[9]) || 30,
    chaveFrota: String(v[10] || '').trim(),
  };
}

/** Situação da troca de óleo e do seguro obrigatório de um veículo (linha da aba Veículos). */
function manutencaoPrevista_(r, cfg) {
  const kmAtual = Number(r[V.KM_ATUAL]) || 0;
  const oleoKm = Number(r[V.OLEO_KM]) || 0;
  const oleo = { kmUltimaTroca: oleoKm || null, dataUltimaTroca: dataBr_(r[V.OLEO_DATA]), intervalo: cfg.oleoIntervalo,
                 agendadaPara: dataBr_(r[V.OLEO_AGENDADA]),
                 agendadaIso: r[V.OLEO_AGENDADA] instanceof Date ? Utilities.formatDate(r[V.OLEO_AGENDADA], TZ, 'yyyy-MM-dd') : '' };
  if (oleoKm > 0) {
    oleo.proximaTrocaKm = oleoKm + cfg.oleoIntervalo;
    oleo.kmFaltando = oleo.proximaTrocaKm - kmAtual;
    oleo.situacao = oleo.kmFaltando <= 0 ? 'Atrasada' : oleo.kmFaltando <= cfg.oleoAviso ? 'Trocar em breve' : 'Em dia';
  } else {
    oleo.situacao = 'Sem registro';
  }
  const seguro = { vencimento: dataBr_(r[V.SEGURO]) };
  const venc = r[V.SEGURO] instanceof Date ? r[V.SEGURO] : null;
  if (venc) {
    const hoje = new Date(Utilities.formatDate(new Date(), TZ, 'yyyy-MM-dd') + 'T00:00:00');
    const dia = new Date(Utilities.formatDate(venc, TZ, 'yyyy-MM-dd') + 'T00:00:00');
    seguro.diasRestantes = Math.round((dia - hoje) / 86400000);
    seguro.situacao = seguro.diasRestantes < 0 ? 'Vencido' : seguro.diasRestantes <= cfg.seguroAviso ? 'Vence em breve' : 'Em dia';
  } else {
    seguro.situacao = 'Sem registro';
  }
  return { oleo, seguro };
}

function veiculoJson_(r, cfg) {
  const m = manutencaoPrevista_(r, cfg);
  return { placa: r[V.PLACA], modelo: r[V.MODELO], ano: r[V.ANO], cor: r[V.COR], kmAtual: r[V.KM_ATUAL],
           kmRestantes: r[V.KM_RESTANTES], situacao: r[V.SITUACAO],
           emManutencao: String(r[V.MANUTENCAO]).toLowerCase() === 'sim', oleo: m.oleo, seguro: m.seguro };
}

function dataBr_(x) {
  return x instanceof Date ? Utilities.formatDate(x, TZ, 'dd/MM/yyyy') : (x ? String(x) : '');
}

/**
 * POST: registra uma leitura, um abastecimento, a checagem diária ou uma troca de óleo.
 * Corpo JSON: { chave, tipo, tecnico, placa, ... }
 *   leitura:       km, periodo ("Início do dia"|"Fim do dia"), origem, fotoBase64 (painel)
 *   abastecimento: km, valor, litros, kmCoube, fotoBase64 (nota fiscal), fotoPainelBase64
 *   checagem:      funcionando (true|false), descricao (problema, quando não funciona)
 *   troca_oleo:    km, observacao
 */
function doPost(e) {
  let d;
  try {
    d = JSON.parse(e.postData.contents);
  } catch (err) {
    return json_({ ok: false, erro: 'Dados inválidos' });
  }
  return json_(registrar_(d));
}

/** Registro usado pelo app Android (POST) e pela versão web (iPhone). */
function registrar_(d) {
  if (d.tipo === 'login') return login_(d);
  if (d.tipo === 'cadastro') return cadastro_(d);
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    if (!autorizado_(d)) return { ok: false, erro: ERRO_LOGIN };
    if (!d.placa || !d.tecnico) return { ok: false, erro: 'Dados incompletos' };
    const ss = SpreadsheetApp.getActive();
    const agora = new Date();
    const cfg = parametros_();

    if (d.tipo === 'checagem') {
      const funcionando = d.funcionando === true || d.funcionando === 'true';
      ss.getSheetByName(ABA.CHECAGEM).appendRow([agora, d.tecnico, d.placa, funcionando ? 'Sim' : 'Não', d.descricao || '']);
      marcarManutencao_(d.placa, !funcionando);
      if (!funcionando) avisarManutencao_(d.placa, d.tecnico, d.descricao || '', agora, cfg);
      SpreadsheetApp.flush();
      return { ok: true, hoje: resumoDia_(d.tecnico, d.placa, agora) };
    }

    if (d.tipo === 'checklist') {
      const nome = String(d.checklist || '');
      const itens = Array.isArray(d.itens) ? d.itens : [];
      if (!CHECKLISTS.includes(nome) || !itens.length) return { ok: false, erro: 'Check-list incompleto' };
      const linhas = itens.map(i => [agora, d.tecnico, d.placa, nome, String(i.item || ''), String(i.condicao || ''), String(i.observacao || '')]);
      const aba = ss.getSheetByName(ABA.CHECKLISTS);
      aba.getRange(aba.getLastRow() + 1, 1, linhas.length, 7).setValues(linhas);
      const problemas = itens.filter(i => i.condicao && i.condicao !== 'OK');
      if (problemas.length && cfg.email) {
        MailApp.sendEmail(cfg.email, `Check-list ${nome}: ${problemas.length} item(ns) com problema (${d.tecnico})`,
          `Prezado Glaydson,\n\nO técnico ${d.tecnico} (veículo ${d.placa}) preencheu o check-list "${nome}" e apontou:\n\n` +
          problemas.map(i => `- ${i.item}: ${i.condicao}${i.observacao ? ' (' + i.observacao + ')' : ''}`).join('\n') +
          `\n\nAtenciosamente,\nControle de Abastecimento GCM Serviços`);
        ss.getSheetByName(ABA.ALERTAS).appendRow([agora, d.placa, d.tecnico, nome, cfg.email, 'Check-list']);
      }
      SpreadsheetApp.flush();
      return { ok: true, problemas: problemas.length, checklists: situacaoChecklists_(d.tecnico, agora) };
    }

    if (d.tipo === 'agendar_oleo') {
      const m = String(d.data || '').match(/^(\d{4})-(\d{2})-(\d{2})$/);
      if (!m) return { ok: false, erro: 'Data inválida' };
      const data = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12, 0, 0);
      const linha = linhaVeiculo_(d.placa);
      if (!linha) return { ok: false, erro: 'Veículo não encontrado' };
      ss.getSheetByName(ABA.VEICULOS).getRange(linha, V.OLEO_AGENDADA + 1).setValue(data);
      SpreadsheetApp.flush();
      if (cfg.email) {
        MailApp.sendEmail(cfg.email, `Troca de óleo agendada: ${d.placa} em ${dataBr_(data)}`,
          `Prezado Glaydson,\n\nO técnico ${d.tecnico} agendou a troca de óleo do veículo ${d.placa} para ${dataBr_(data)}.\n\n` +
          `Atenciosamente,\nControle de Abastecimento GCM Serviços`);
      }
      const r = linhas_(ss.getSheetByName(ABA.VEICULOS)).find(l => l[V.PLACA] === d.placa);
      return { ok: true, veiculo: veiculoJson_(r, cfg) };
    }

    const km = Number(d.km);
    if (!(km > 0)) return { ok: false, erro: 'Informe o km' };

    if (d.tipo === 'troca_oleo') {
      ss.getSheetByName(ABA.OLEO).appendRow([agora, d.tecnico, d.placa, km, d.observacao || '']);
      const linha = linhaVeiculo_(d.placa);
      if (linha) {
        const aba = ss.getSheetByName(ABA.VEICULOS);
        aba.getRange(linha, V.OLEO_KM + 1).setValue(km);
        aba.getRange(linha, V.OLEO_DATA + 1).setValue(agora);
        aba.getRange(linha, V.OLEO_AGENDADA + 1).setValue('');
      }
      SpreadsheetApp.flush();
      return { ok: true, relatorio: relatorio_(d.tecnico, d.placa, agora) };
    }

    const foto = d.fotoBase64 ? salvarFoto_(d.fotoBase64, d.placa, agora) : '';
    if (d.tipo === 'abastecimento') {
      const litros = Number(d.litros) || '';
      const kmCoube = Number(d.kmCoube) || (litros ? Math.round(litros * cfg.consumo) : '');
      if (!kmCoube) return { ok: false, erro: 'Informe os km que couberam no tanque ou os litros' };
      const fotoPainel = d.fotoPainelBase64 ? salvarFoto_(d.fotoPainelBase64, d.placa + '_painel', agora) : '';
      ss.getSheetByName(ABA.ABASTECIMENTOS).appendRow([agora, d.tecnico, d.placa, km, Number(d.valor) || '', litros, kmCoube, foto, fotoPainel]);
    } else {
      ss.getSheetByName(ABA.LEITURAS).appendRow([agora, d.tecnico, d.placa, km, d.periodo || '', foto, d.origem || 'Leitura automática']);
    }
    SpreadsheetApp.flush();

    const status = situacaoVeiculo_(d.placa);
    let alertaEnviado = false;
    if (status && status.situacao === 'Abastecer' && d.tipo !== 'abastecimento') {
      alertaEnviado = enviarAlerta_(status, d.tecnico, agora);
    }
    try { avisosPrevistos_(d.placa, d.tecnico, agora, cfg); } catch (err) { console.error(err); }
    const hoje = resumoDia_(d.tecnico, d.placa, agora);
    return { ok: true, situacao: status, alertaEnviado, kmDoDia: hoje.kmRodados, hoje };
  } catch (err) {
    return { ok: false, erro: String(err) };
  } finally {
    lock.releaseLock();
  }
}

/** Número da linha (base 1) do veículo na aba Veículos, ou 0. */
function linhaVeiculo_(placa) {
  const i = linhas_(SpreadsheetApp.getActive().getSheetByName(ABA.VEICULOS)).findIndex(r => r[V.PLACA] === placa);
  return i < 0 ? 0 : i + 2;
}

function marcarManutencao_(placa, emManutencao) {
  const linha = linhaVeiculo_(placa);
  if (linha) SpreadsheetApp.getActive().getSheetByName(ABA.VEICULOS).getRange(linha, V.MANUTENCAO + 1).setValue(emManutencao ? 'Sim' : 'Não');
}

/** E-mail ao responsável quando o técnico informa que o veículo não está funcionando. */
function avisarManutencao_(placa, tecnico, descricao, agora, cfg) {
  if (!cfg.email) return;
  const s = situacaoVeiculo_(placa) || { placa };
  MailApp.sendEmail(cfg.email, `Veículo ${placa} em manutenção (informado por ${tecnico})`,
    `Prezado Glaydson,\n\n` +
    `O técnico ${tecnico} informou que o veículo ${placa}${s.modelo ? ' - ' + s.modelo : ''} não está funcionando corretamente.\n\n` +
    `Problema informado: ${descricao || '(não descrito)'}\n` +
    `Data e hora: ${Utilities.formatDate(agora, TZ, 'dd/MM/yyyy HH:mm')}\n\n` +
    `O veículo foi marcado como "Em manutenção" na planilha. Quando o técnico confirmar que voltou a funcionar, a marcação é retirada.\n\n` +
    `Atenciosamente,\nControle de Abastecimento GCM Serviços`);
  SpreadsheetApp.getActive().getSheetByName(ABA.ALERTAS).appendRow([agora, placa, tecnico, '', cfg.email, 'Manutenção']);
}

/** Avisos por e-mail de troca de óleo e de seguro obrigatório, um por ciclo. */
function avisosPrevistos_(placa, tecnico, agora, cfg) {
  if (!cfg.email) return;
  const ss = SpreadsheetApp.getActive();
  const r = linhas_(ss.getSheetByName(ABA.VEICULOS)).find(l => l[V.PLACA] === placa);
  if (!r) return;
  const m = manutencaoPrevista_(r, cfg);
  const alertas = linhas_(ss.getSheetByName(ABA.ALERTAS)).filter(a => a[1] === placa);
  const aba = ss.getSheetByName(ABA.ALERTAS);

  if (m.oleo.situacao === 'Trocar em breve' || m.oleo.situacao === 'Atrasada') {
    const ref = 'Óleo ' + m.oleo.proximaTrocaKm;
    if (!alertas.some(a => a[5] === 'Óleo' && String(a[3]) === ref)) {
      MailApp.sendEmail(cfg.email, `Troca de óleo do veículo ${placa}`,
        `Prezado Glaydson,\n\nO veículo ${placa} está próximo da troca de óleo.\n\n` +
        `Km atual: ${r[V.KM_ATUAL]}\nPróxima troca: ${m.oleo.proximaTrocaKm} km\n` +
        `Faltam: ${m.oleo.kmFaltando} km\nTécnico responsável: ${tecnico}\n\n` +
        `Atenciosamente,\nControle de Abastecimento GCM Serviços`);
      aba.appendRow([agora, placa, tecnico, ref, cfg.email, 'Óleo']);
    }
  }
  if (m.seguro.situacao === 'Vence em breve' || m.seguro.situacao === 'Vencido') {
    const ref = 'Seguro ' + m.seguro.vencimento;
    if (!alertas.some(a => a[5] === 'Seguro' && String(a[3]) === ref)) {
      MailApp.sendEmail(cfg.email, `Seguro obrigatório do veículo ${placa}`,
        `Prezado Glaydson,\n\nO seguro obrigatório do veículo ${placa} ` +
        (m.seguro.diasRestantes < 0 ? `venceu em ${m.seguro.vencimento}.` : `vence em ${m.seguro.vencimento} (faltam ${m.seguro.diasRestantes} dias).`) +
        `\n\nAtenciosamente,\nControle de Abastecimento GCM Serviços`);
      aba.appendRow([agora, placa, tecnico, ref, cfg.email, 'Seguro']);
    }
  }
}

function situacaoVeiculo_(placa) {
  const r = linhas_(SpreadsheetApp.getActive().getSheetByName(ABA.VEICULOS)).find(l => l[0] === placa);
  if (!r) return null;
  return { placa: r[0], modelo: r[1], cor: r[3], kmCoube: r[4], kmUltimoAbastecimento: r[5],
           kmAtual: r[6], kmRodados: r[7], kmRestantes: r[8], litrosRestantes: r[9], situacao: r[10] };
}

/**
 * Resumo do dia do técnico: km rodados (maior km do dia menos o menor, contando
 * leituras e abastecimentos) e gasto estimado de combustível
 * (km rodados / consumo x preço por litro do último abastecimento do veículo).
 */
function resumoDia_(tecnico, placa, agora) {
  const ss = SpreadsheetApp.getActive();
  const tz = TZ;
  const dia = Utilities.formatDate(agora, tz, 'yyyy-MM-dd');
  const doDia = r => r[0] && Utilities.formatDate(new Date(r[0]), tz, 'yyyy-MM-dd') === dia;
  const leituras = linhas_(ss.getSheetByName(ABA.LEITURAS)).filter(r => r[1] === tecnico && r[2] === placa && doDia(r));
  const abastecimentos = linhas_(ss.getSheetByName(ABA.ABASTECIMENTOS)).filter(r => r[2] === placa);
  const abastHoje = abastecimentos.filter(r => r[1] === tecnico && doDia(r));
  const kms = leituras.map(r => Number(r[3])).concat(abastHoje.map(r => Number(r[3]))).filter(n => n > 0);
  const kmRodados = kms.length ? Math.max(...kms) - Math.min(...kms) : 0;
  const consumo = Number(config_().getRange('B2').getValue()) || 10;
  const ultimo = abastecimentos.filter(r => Number(r[4]) > 0 && Number(r[5]) > 0).pop();
  const precoLitro = ultimo ? Number(ultimo[4]) / Number(ultimo[5]) : 0;
  const litros = kmRodados / consumo;
  const checagens = linhas_(ss.getSheetByName(ABA.CHECAGEM)).filter(r => r[2] === placa && doDia(r));
  const veiculo = linhas_(ss.getSheetByName(ABA.VEICULOS)).find(r => r[V.PLACA] === placa);
  return {
    kmRodados,
    litros: Math.round(litros * 100) / 100,
    precoLitro: Math.round(precoLitro * 100) / 100,
    gasto: Math.round(litros * precoLitro * 100) / 100,
    inicioFeito: leituras.some(r => r[4] === 'Início do dia'),
    abastecimentoFeito: abastHoje.length > 0,
    fimFeito: leituras.some(r => r[4] === 'Fim do dia'),
    checagemFeita: checagens.length > 0,
    emManutencao: !!veiculo && String(veiculo[V.MANUTENCAO]).toLowerCase() === 'sim',
  };
}

function config_() {
  return SpreadsheetApp.getActive().getSheetByName(ABA.CONFIG);
}

/** Envia o alerta ao responsável uma única vez por ciclo de abastecimento. */
function enviarAlerta_(s, nomeTecnico, agora) {
  const ss = SpreadsheetApp.getActive();
  const ultimoAbastecimento = linhas_(ss.getSheetByName(ABA.ABASTECIMENTOS))
    .filter(r => r[2] === s.placa).map(r => new Date(r[0]).getTime());
  const desde = ultimoAbastecimento.length ? Math.max(...ultimoAbastecimento) : 0;
  const jaAvisado = linhas_(ss.getSheetByName(ABA.ALERTAS))
    .some(r => r[1] === s.placa && (!r[5] || r[5] === 'Combustível') && new Date(r[0]).getTime() > desde);
  if (jaAvisado) return false;

  const destinos = String(config_().getRange('B4').getValue()).trim();
  if (!destinos) return false;

  const assunto = `O técnico ${nomeTecnico} precisa abastecer (${s.placa})`;
  const corpo =
    `Prezado Glaydson,\n\n` +
    `O técnico ${nomeTecnico} precisa abastecer o veículo ${s.placa}` +
    `${s.modelo ? ' - ' + s.modelo : ''}${s.cor ? ' (' + s.cor + ')' : ''}.\n\n` +
    `Km atual: ${s.kmAtual}\n` +
    `Km no último abastecimento: ${s.kmUltimoAbastecimento}\n` +
    `Km que couberam no tanque: ${s.kmCoube}\n` +
    `Km rodados desde então: ${s.kmRodados}\n` +
    `Km restantes estimados: ${s.kmRestantes}\n\n` +
    `Atenciosamente,\nControle de Abastecimento GCM Serviços`;
  MailApp.sendEmail(destinos, assunto, corpo);
  ss.getSheetByName(ABA.ALERTAS).appendRow([agora, s.placa, nomeTecnico, s.kmRestantes, destinos, 'Combustível']);
  return true;
}

function salvarFoto_(base64, placa, data) {
  const pastas = DriveApp.getFoldersByName(PASTA_FOTOS);
  const pasta = pastas.hasNext() ? pastas.next() : DriveApp.createFolder(PASTA_FOTOS);
  const nome = `${placa}_${Utilities.formatDate(data, 'America/Sao_Paulo', 'yyyyMMdd_HHmmss')}.jpg`;
  const blob = Utilities.newBlob(Utilities.base64Decode(base64), 'image/jpeg', nome);
  return pasta.createFile(blob).getUrl();
}

function linhas_(aba) {
  const n = aba.getLastRow();
  return n < 2 ? [] : aba.getRange(2, 1, n - 1, aba.getLastColumn()).getValues();
}

/** A chave fica na aba Configurações (B8) ou na propriedade do script CHAVE_APP. */
const ERRO_LOGIN = 'Entre novamente: nome ou senha incorretos.';

/** Linha do técnico ativo na aba Técnicos (A nome, B e-mail, C placa, D ativo, E senha). */
function tecnicoPorNome_(nome) {
  const alvo = String(nome || '').trim().toLowerCase();
  if (!alvo) return null;
  const aba = SpreadsheetApp.getActive().getSheetByName(ABA.TECNICOS);
  const linhas = linhas_(aba);
  const i = linhas.findIndex(r => String(r[0]).trim().toLowerCase() === alvo && String(r[3]).toLowerCase() !== 'não');
  if (i < 0) return null;
  const r = linhas[i];
  return { linha: i + 2, nome: String(r[0]).trim(), email: r[1], placa: String(r[2] || '').trim(), senha: String(r[4] == null ? '' : r[4]).trim(), aba };
}

/** Aceita a senha do técnico (novo login) ou a chave antiga (versões anteriores do app). */
function autorizado_(d) {
  if (d.chave && chaveValida_(d.chave)) return true;
  const t = tecnicoPorNome_(d.tecnico);
  if (!t || !t.senha || d.senha == null) return false;
  const cache = CacheService.getScriptCache();
  const chaveCache = 'falhas_' + t.nome.toLowerCase();
  const falhas = Number(cache.get(chaveCache)) || 0;
  if (falhas >= 5) return false;
  if (String(d.senha).trim() === t.senha) return true;
  cache.put(chaveCache, String(falhas + 1), 900);
  return false;
}

/** Nomes dos técnicos ativos, para a tela de login. */
function listaTecnicos_() {
  const tecnicos = linhas_(SpreadsheetApp.getActive().getSheetByName(ABA.TECNICOS))
    .filter(r => r[0] && String(r[3]).toLowerCase() !== 'não')
    .map(r => ({ nome: String(r[0]).trim(), temSenha: String(r[4] == null ? '' : r[4]).trim() !== '' }));
  return { ok: true, tecnicos };
}

/**
 * Login do técnico: { tecnico, senha }. No primeiro acesso (senha em branco na aba Técnicos),
 * a senha digitada passa a ser a senha do técnico. Para trocar a senha, apague a célula na coluna E.
 * Após 5 tentativas erradas, o técnico fica bloqueado por 15 minutos.
 */
function login_(d) {
  const t = tecnicoPorNome_(d.tecnico);
  if (!t) return { ok: false, erro: 'Técnico não encontrado. Peça ao responsável para cadastrar você.' };
  const senha = String(d.senha == null ? '' : d.senha).trim();
  if (!/^\d{4}$/.test(senha)) return { ok: false, erro: 'A senha deve ter 4 números.' };
  const cache = CacheService.getScriptCache();
  const chaveCache = 'falhas_' + t.nome.toLowerCase();
  const falhas = Number(cache.get(chaveCache)) || 0;
  if (falhas >= 5) return { ok: false, erro: 'Muitas tentativas. Aguarde 15 minutos e tente de novo.' };
  let primeiroAcesso = false;
  if (!t.senha) {
    const trava = LockService.getScriptLock();
    trava.waitLock(20000);
    try {
      t.aba.getRange(t.linha, 5).setNumberFormat('@').setValue(senha);
      SpreadsheetApp.flush();
    } finally {
      trava.releaseLock();
    }
    primeiroAcesso = true;
  } else if (senha !== t.senha) {
    cache.put(chaveCache, String(falhas + 1), 900);
    return { ok: false, erro: 'Senha incorreta.' };
  }
  cache.remove(chaveCache);
  const ss = SpreadsheetApp.getActive();
  const cfg = parametros_();
  const placas = linhas_(ss.getSheetByName(ABA.VEICULOS)).filter(r => r[V.PLACA]).map(r => String(r[V.PLACA]));
  return {
    ok: true, primeiroAcesso, tecnico: t.nome, placa: placas.includes(t.placa) ? t.placa : '', placas,
    config: { consumoKmL: cfg.consumo, antecedenciaKm: cfg.antecedencia, valorPadrao: cfg.valorPadrao,
              lembreteInicio: cfg.lembreteInicio, lembreteFim: cfg.lembreteFim },
  };
}

/**
 * Cadastro feito pelo próprio técnico no primeiro acesso:
 * { nome, email, placa, modelo, ano, cor, senha }.
 * Cria o técnico na aba Técnicos e, se a placa ainda não existir, o veículo na aba Veículos.
 * O responsável recebe um e-mail avisando do novo cadastro.
 */
function cadastro_(d) {
  const nome = String(d.nome || '').trim().replace(/\s+/g, ' ');
  const placa = String(d.placa || '').trim().toUpperCase().replace(/[^A-Z0-9]/g, '');
  const senha = String(d.senha == null ? '' : d.senha).trim();
  const email = String(d.email || '').trim();
  if (nome.split(' ').length < 2) return { ok: false, erro: 'Digite seu nome e sobrenome.' };
  if (placa.length < 6 || placa.length > 8) return { ok: false, erro: 'Digite a placa do veículo (ex.: ABC1D23).' };
  if (!/^\d{4}$/.test(senha)) return { ok: false, erro: 'A senha deve ter 4 números.' };
  const trava = LockService.getScriptLock();
  trava.waitLock(30000);
  try {
    const ss = SpreadsheetApp.getActive();
    const abaTec = ss.getSheetByName(ABA.TECNICOS);
    const existentes = linhas_(abaTec).filter(r => String(r[0]).trim().toLowerCase() === nome.toLowerCase());
    if (existentes.length) return { ok: false, erro: 'Já existe um cadastro com este nome. Volte e entre com a sua senha.' };

    const abaV = ss.getSheetByName(ABA.VEICULOS);
    const nV = abaV.getLastRow();
    const placas = nV >= 2 ? abaV.getRange(2, 1, nV - 1, 1).getValues().map(r => String(r[0]).trim()) : [];
    const existe = placas.some(p => p.toUpperCase().replace(/[^A-Z0-9]/g, '') === placa);
    const placaFinal = existe ? placas.find(p => p.toUpperCase().replace(/[^A-Z0-9]/g, '') === placa) : placa;
    if (!existe) {
      // Primeira linha vazia na coluna A (as colunas E:K são calculadas pelas fórmulas da linha 2).
      let i = placas.findIndex(p => !p);
      if (i < 0) i = placas.length;
      abaV.getRange(i + 2, 1, 1, 4).setValues([[placa, String(d.modelo || '').trim(), String(d.ano || '').trim(), String(d.cor || '').trim()]]);
      abaV.getRange(i + 2, V.MANUTENCAO + 1).setValue('Não');
    }

    const nT = abaTec.getLastRow();
    const nomesT = nT >= 2 ? abaTec.getRange(2, 1, nT - 1, 1).getValues().map(r => String(r[0]).trim()) : [];
    let j = nomesT.findIndex(x => !x);
    if (j < 0) j = nomesT.length;
    abaTec.getRange(j + 2, 5).setNumberFormat('@');
    abaTec.getRange(j + 2, 1, 1, 5).setValues([[nome, email, placaFinal, 'Sim', senha]]);
    SpreadsheetApp.flush();

    const cfg = parametros_();
    if (cfg.email) {
      try {
        MailApp.sendEmail(cfg.email, `Novo técnico cadastrado: ${nome} (${placaFinal})`,
          `Prezado Glaydson,\n\n${nome} fez o cadastro no aplicativo de combustível.\n\n` +
          `Veículo: ${placaFinal}${existe ? ' (já cadastrado)' : ' ' + [d.modelo, d.ano, d.cor].filter(Boolean).join(' · ') + ' (novo)'}\n` +
          (email ? `E-mail: ${email}\n` : '') +
          `\nPara desativar, coloque "Não" na coluna "Ativo" da aba Técnicos.\n\nAtenciosamente,\nControle de Abastecimento GCM Serviços`);
      } catch (err) { console.error(err); }
    }
    const placasTodas = linhas_(abaV).filter(r => r[V.PLACA]).map(r => String(r[V.PLACA]));
    return {
      ok: true, primeiroAcesso: true, tecnico: nome, placa: placaFinal, placas: placasTodas,
      config: { consumoKmL: cfg.consumo, antecedenciaKm: cfg.antecedencia, valorPadrao: cfg.valorPadrao,
                lembreteInicio: cfg.lembreteInicio, lembreteFim: cfg.lembreteFim },
    };
  } finally {
    trava.releaseLock();
  }
}

/** Funções chamadas pela versão web (google.script.run). Devolvem texto JSON. */
function webConsultar(p) {
  try { return JSON.stringify(consultar_(p || {})); } catch (err) { return JSON.stringify({ ok: false, erro: String(err) }); }
}
function webRegistrar(d) {
  try { return JSON.stringify(registrar_(d || {})); } catch (err) { return JSON.stringify({ ok: false, erro: String(err) }); }
}

function chaveValida_(chave) {
  const esperada = PropertiesService.getScriptProperties().getProperty('CHAVE_APP') ||
    String(config_().getRange('B8').getValue()).trim();
  return !!esperada && chave === esperada;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

// ------------------------------------------------------------------ Relatório do técnico

/** Histórico de combustível, troca de óleo e seguro do veículo, para o relatório do app. */
function relatorio_(tecnico, placa, agora) {
  const ss = SpreadsheetApp.getActive();
  const cfg = parametros_();
  const dia = x => Utilities.formatDate(new Date(x), TZ, 'yyyy-MM-dd');
  const mes = Utilities.formatDate(agora, TZ, 'yyyy-MM');
  const abast = linhas_(ss.getSheetByName(ABA.ABASTECIMENTOS)).filter(r => r[0] && r[2] === placa);
  const leit = linhas_(ss.getSheetByName(ABA.LEITURAS)).filter(r => r[0] && r[2] === placa);
  const ultimoComPreco = abast.filter(r => Number(r[4]) > 0 && Number(r[5]) > 0).pop();
  const precoLitro = ultimoComPreco ? Number(ultimoComPreco[4]) / Number(ultimoComPreco[5]) : 0;

  const doMes = abast.filter(r => dia(r[0]).slice(0, 7) === mes);
  const kmsMes = leit.concat(abast).filter(r => dia(r[0]).slice(0, 7) === mes).map(r => Number(r[3])).filter(n => n > 0);

  const dias = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(agora.getTime() - i * 86400000);
    const chave = dia(d);
    const kms = leit.concat(abast).filter(r => r[1] === tecnico && dia(r[0]) === chave).map(r => Number(r[3])).filter(n => n > 0);
    const km = kms.length ? Math.max(...kms) - Math.min(...kms) : 0;
    dias.push({ dia: Utilities.formatDate(d, TZ, 'dd/MM'), km, gasto: arred_(km / cfg.consumo * precoLitro) });
  }

  const r = linhas_(ss.getSheetByName(ABA.VEICULOS)).find(l => l[V.PLACA] === placa);
  const m = r ? manutencaoPrevista_(r, cfg) : { oleo: { situacao: 'Sem registro' }, seguro: { situacao: 'Sem registro' } };
  return {
    placa, kmAtual: r ? Number(r[V.KM_ATUAL]) || 0 : 0, precoLitro: arred_(precoLitro), consumoKmL: cfg.consumo,
    mes: {
      gasto: arred_(doMes.reduce((t, x) => t + (Number(x[4]) || 0), 0)),
      litros: arred_(doMes.reduce((t, x) => t + (Number(x[5]) || 0), 0)),
      km: kmsMes.length ? Math.max(...kmsMes) - Math.min(...kmsMes) : 0,
      abastecimentos: doMes.length,
    },
    dias,
    abastecimentos: abast.slice(-10).reverse().map(x => ({
      data: Utilities.formatDate(new Date(x[0]), TZ, 'dd/MM/yyyy HH:mm'), tecnico: x[1], km: Number(x[3]) || 0,
      valor: Number(x[4]) || 0, litros: Number(x[5]) || 0,
      precoLitro: Number(x[4]) > 0 && Number(x[5]) > 0 ? arred_(x[4] / x[5]) : 0,
    })),
    oleo: m.oleo, seguro: m.seguro,
  };
}

function arred_(n) { return Math.round((Number(n) || 0) * 100) / 100; }

// ------------------------------------------------------------------ Check-lists semanais

/** Segunda-feira 00:00 da semana de "data" (fuso de São Paulo), como texto yyyy-MM-dd. */
function inicioSemana_(data) {
  const d = new Date(Utilities.formatDate(data, TZ, 'yyyy-MM-dd') + 'T12:00:00');
  const dow = (d.getDay() + 6) % 7; // segunda = 0
  return Utilities.formatDate(new Date(d.getTime() - dow * 86400000), TZ, 'yyyy-MM-dd');
}

/** Itens ativos de cada check-list e a situação do técnico na semana. */
function situacaoChecklists_(tecnico, agora) {
  const ss = SpreadsheetApp.getActive();
  const itens = linhas_(ss.getSheetByName(ABA.ITENS)).filter(r => r[0] && r[1] && String(r[2]).toLowerCase() !== 'não');
  const resp = linhas_(ss.getSheetByName(ABA.CHECKLISTS)).filter(r => r[0] && r[1] === tecnico);
  const semana = inicioSemana_(agora);
  return CHECKLISTS.map(nome => {
    const doTipo = resp.filter(r => r[3] === nome);
    const ultimo = doTipo.length ? doTipo[doTipo.length - 1][0] : null;
    const ultimas = ultimo ? doTipo.filter(r => new Date(r[0]).getTime() === new Date(ultimo).getTime()) : [];
    return {
      nome,
      itens: itens.filter(r => r[0] === nome).map(r => String(r[1])),
      ultimoEm: ultimo ? Utilities.formatDate(new Date(ultimo), TZ, 'dd/MM/yyyy HH:mm') : '',
      feitoNaSemana: !!ultimo && Utilities.formatDate(new Date(ultimo), TZ, 'yyyy-MM-dd') >= semana,
      problemas: ultimas.filter(r => r[5] && r[5] !== 'OK').map(r => ({ item: r[4], condicao: r[5], observacao: r[6] })),
    };
  });
}

// ------------------------------------------------------------------ Gestão de frota

/** Página de gestão de frota (painel). Abra: <URL do app>?pagina=frota&chave=<Configurações!B12> */
function paginaFrota_(chave) {
  const cfg = parametros_();
  const url = ScriptApp.getService().getUrl();
  let corpo;
  if (!cfg.chaveFrota || chave !== cfg.chaveFrota) {
    corpo = `<div class="login"><img src="${LOGO_URL_}" alt="GCM" onerror="this.remove()"><h1>Gestão de frota</h1>
      <p>Digite a chave da gestão de frota (aba Configurações, célula B12).</p>
      <form method="get" action="${url}" target="_top"><input type="hidden" name="pagina" value="frota">
      <input name="chave" type="password" placeholder="Chave" required><button>Entrar</button></form>
      ${chave ? '<p class="erro">Chave incorreta.</p>' : ''}</div>`;
  } else {
    const dados = JSON.stringify(dadosFrota_(cfg)).replace(/</g, '\\u003c');
    corpo = `<div id="app"></div><script>const D=${dados};const URL_FROTA=${JSON.stringify(url + '?pagina=frota&chave=' + encodeURIComponent(chave))};const CHAVE=${JSON.stringify(chave).replace(/</g, '\\u003c')};</script><script>${JS_FROTA_}</script>`;
  }
  return HtmlService.createHtmlOutput(`<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Gestão de frota GCM</title><style>${CSS_FROTA_}</style></head><body>${corpo}</body></html>`)
    .setTitle('Gestão de frota GCM')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

/**
 * Exclui um veículo da aba Veículos (chamado pelo botão "Excluir" da gestão de frota).
 * O histórico (abastecimentos, leituras, checagens, check-lists, trocas de óleo e alertas) é mantido.
 * As linhas abaixo sobem uma posição; a linha 2 não é apagada porque guarda as fórmulas das colunas E:K.
 */
function excluirVeiculo(chave, placa) {
  const cfg = parametros_();
  if (!cfg.chaveFrota || chave !== cfg.chaveFrota) throw new Error('Chave da gestão de frota inválida.');
  placa = String(placa || '').trim();
  if (!placa) throw new Error('Informe o veículo.');
  const trava = LockService.getScriptLock();
  trava.waitLock(20000);
  try {
    const ss = SpreadsheetApp.getActive();
    const aba = ss.getSheetByName(ABA.VEICULOS);
    const n = aba.getLastRow() - 1;
    if (n < 1) throw new Error('Veículo não encontrado.');
    const dados = aba.getRange(2, 1, n, V.PLACA + 4).getValues();          // A:D
    const extras = aba.getRange(2, V.OLEO_KM + 1, n, V.OLEO_AGENDADA - V.OLEO_KM + 1).getValues(); // L:P
    const i = dados.findIndex(r => String(r[V.PLACA]).trim() === placa);
    if (i < 0) throw new Error('Veículo não encontrado.');
    const tira = (m, largura) => m.slice(0, i).concat(m.slice(i + 1), [Array(largura).fill('')]);
    aba.getRange(2, 1, n, 4).setValues(tira(dados, 4));
    aba.getRange(2, V.OLEO_KM + 1, n, extras[0].length).setValues(tira(extras, extras[0].length));
    const vinculados = linhas_(ss.getSheetByName(ABA.TECNICOS)).filter(t => t[0] && t[2] === placa).map(t => t[0]);
    ss.getSheetByName(ABA.ALERTAS).appendRow([new Date(), placa, vinculados.join(', '), '', '', 'Veículo excluído']);
    SpreadsheetApp.flush();
    return { ok: true, placa, tecnicos: vinculados };
  } finally {
    trava.releaseLock();
  }
}

function dadosFrota_(cfg) {
  const ss = SpreadsheetApp.getActive();
  const agora = new Date();
  const dia = x => Utilities.formatDate(new Date(x), TZ, 'yyyy-MM-dd');
  const mes = Utilities.formatDate(agora, TZ, 'yyyy-MM');
  const veiculos = linhas_(ss.getSheetByName(ABA.VEICULOS)).filter(r => r[V.PLACA]);
  const tecnicos = linhas_(ss.getSheetByName(ABA.TECNICOS)).filter(r => r[0] && String(r[3]).toLowerCase() !== 'não');
  const abast = linhas_(ss.getSheetByName(ABA.ABASTECIMENTOS)).filter(r => r[0]);
  const leit = linhas_(ss.getSheetByName(ABA.LEITURAS)).filter(r => r[0]);
  const checagens = linhas_(ss.getSheetByName(ABA.CHECAGEM)).filter(r => r[0]);
  const alertas = linhas_(ss.getSheetByName(ABA.ALERTAS)).filter(r => r[0]);
  const fmt = x => Utilities.formatDate(new Date(x), TZ, 'dd/MM/yyyy HH:mm');

  const kmNoMes = (placa, tecnico) => {
    const kms = leit.concat(abast).filter(r => r[2] === placa && (!tecnico || r[1] === tecnico) && dia(r[0]).slice(0, 7) === mes)
      .map(r => Number(r[3])).filter(n => n > 0);
    return kms.length ? Math.max(...kms) - Math.min(...kms) : 0;
  };
  const doMes = abast.filter(r => dia(r[0]).slice(0, 7) === mes);

  const frota = veiculos.map(r => {
    const m = manutencaoPrevista_(r, cfg);
    const ult = checagens.filter(c => c[2] === r[V.PLACA]).pop();
    return {
      placa: r[V.PLACA], modelo: r[V.MODELO], cor: r[V.COR], ano: r[V.ANO],
      tecnicos: tecnicos.filter(t => t[2] === r[V.PLACA]).map(t => t[0]).join(', '),
      kmAtual: Number(r[V.KM_ATUAL]) || 0, kmRestantes: Number(r[V.KM_RESTANTES]) || 0, situacao: r[V.SITUACAO],
      emManutencao: String(r[V.MANUTENCAO]).toLowerCase() === 'sim', oleo: m.oleo, seguro: m.seguro,
      ultimaChecagem: ult ? { data: fmt(ult[0]), funcionando: ult[3], problema: ult[4], tecnico: ult[1] } : null,
      kmMes: kmNoMes(r[V.PLACA]),
      gastoMes: arred_(doMes.filter(a => a[2] === r[V.PLACA]).reduce((t, a) => t + (Number(a[4]) || 0), 0)),
    };
  });

  const dias = [];
  for (let i = 29; i >= 0; i--) {
    const d = new Date(agora.getTime() - i * 86400000);
    const k = dia(d);
    dias.push({ dia: Utilities.formatDate(d, TZ, 'dd/MM'),
      gasto: arred_(abast.filter(a => dia(a[0]) === k).reduce((t, a) => t + (Number(a[4]) || 0), 0)) });
  }

  const porTecnico = tecnicos.map(t => ({
    nome: t[0], placa: t[2], kmMes: kmNoMes(t[2], t[0]),
    gastoMes: arred_(doMes.filter(a => a[1] === t[0]).reduce((s, a) => s + (Number(a[4]) || 0), 0)),
    abastecimentos: doMes.filter(a => a[1] === t[0]).length,
  }));

  const gastoMes = arred_(doMes.reduce((t, a) => t + (Number(a[4]) || 0), 0));
  const kmMes = frota.reduce((t, f) => t + f.kmMes, 0);
  return {
    atualizado: Utilities.formatDate(agora, TZ, 'dd/MM/yyyy HH:mm'),
    mesNome: Utilities.formatDate(agora, TZ, 'MM/yyyy'),
    kpis: {
      veiculos: frota.length,
      manutencao: frota.filter(f => f.emManutencao).length,
      abastecer: frota.filter(f => f.situacao === 'Abastecer').length,
      pendencias: frota.filter(f => ['Trocar em breve', 'Atrasada'].includes(f.oleo.situacao) ||
                                    ['Vence em breve', 'Vencido'].includes(f.seguro.situacao)).length,
      gastoMes, kmMes,
      litrosMes: arred_(doMes.reduce((t, a) => t + (Number(a[5]) || 0), 0)),
      custoKm: kmMes > 0 ? arred_(gastoMes / kmMes) : 0,
    },
    frota, dias, porTecnico,
    abastecimentos: abast.slice(-15).reverse().map(a => ({ data: fmt(a[0]), tecnico: a[1], placa: a[2], km: Number(a[3]) || 0,
      valor: Number(a[4]) || 0, litros: Number(a[5]) || 0, nota: a[7] || '' })),
    ocorrencias: checagens.filter(c => c[3] === 'Não').slice(-10).reverse()
      .map(c => ({ data: fmt(c[0]), tecnico: c[1], placa: c[2], problema: c[4] })),
    alertas: alertas.filter(a => a[5] !== 'Manutenção').slice(-10).reverse().map(a => ({ data: fmt(a[0]), placa: a[1], tecnico: a[2], tipo: a[5] || 'Combustível' })),
    checklists: tecnicos.map(t => ({ nome: t[0], placa: t[2], lista: situacaoChecklists_(t[0], agora)
      .map(c => ({ nome: c.nome, ultimoEm: c.ultimoEm, feitoNaSemana: c.feitoNaSemana, problemas: c.problemas })) })),
    planilha: SpreadsheetApp.getActive().getUrl(),
  };
}

const LOGO_BASE_ = 'https://raw.githubusercontent.com/glaydsonmj-ops/Abastecimento_Repositorio_GCM/main/app/src/main/res/drawable-nodpi/';
const LOGO_URL_ = LOGO_BASE_ + 'logo_gcm.png';
const SIMBOLO_URL_ = LOGO_BASE_ + 'logo_g.png';

const CSS_FROTA_ = `
:root{--marinho:#052545;--azul:#0E3A66;--magenta:#D1006C;--laranja:#FF7A12;--fundo:#F3F5F9;--texto:#14213D;--suave:#6B7588;
--linha:#E3E7EE;--verde:#1B9E5A;--verde-c:#E5F6EC;--vermelho:#D32F2F;--vermelho-c:#FDECEC;--laranja-c:#FFF1E6}
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--fundo);color:var(--texto)}
header{background:linear-gradient(135deg,var(--marinho),var(--azul));color:#fff;padding:18px 20px}
header .topo{display:flex;align-items:center;gap:14px;max-width:1200px;margin:0 auto}
header .marca{background:#fff;border-radius:14px;padding:6px 8px;display:flex}header .marca img{height:40px}
header h1{margin:0;font-size:22px}header p{margin:2px 0 0;opacity:.75;font-size:13px}
header .acoes{margin-left:auto;display:flex;gap:8px;flex-wrap:wrap}
.faixa{height:4px;background:linear-gradient(90deg,var(--magenta),var(--laranja))}
a.btn,button{border:0;border-radius:12px;padding:10px 16px;font-weight:700;cursor:pointer;text-decoration:none;font-size:14px}
a.btn{background:rgba(255,255,255,.15);color:#fff}
main{max-width:1200px;margin:0 auto;padding:18px 16px 40px;display:grid;gap:16px}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px}
.kpi{background:#fff;border-radius:18px;padding:16px;box-shadow:0 1px 3px rgba(5,37,69,.08)}
.kpi small{color:var(--suave);font-size:13px}.kpi b{display:block;font-size:26px;margin-top:4px}
.kpi.alerta b{color:var(--vermelho)}.kpi.marca b{color:var(--magenta)}
.cartao{background:#fff;border-radius:18px;padding:18px;box-shadow:0 1px 3px rgba(5,37,69,.08);overflow:hidden}
.cartao h2{margin:0 0 12px;font-size:17px}
.duas{display:grid;grid-template-columns:2fr 1fr;gap:16px}@media(max-width:860px){.duas{grid-template-columns:1fr}}
.tabela{overflow-x:auto}table{width:100%;border-collapse:collapse;font-size:14px}
th{text-align:left;color:var(--suave);font-weight:600;font-size:12px;text-transform:uppercase;letter-spacing:.03em;padding:8px;border-bottom:1px solid var(--linha);white-space:nowrap}
td{padding:10px 8px;border-bottom:1px solid var(--linha);vertical-align:top}tr:last-child td{border-bottom:0}
.sub{color:var(--suave);font-size:12px}
.selo{display:inline-block;border-radius:999px;padding:3px 10px;font-size:12px;font-weight:700;white-space:nowrap}
.ok{background:var(--verde-c);color:var(--verde)}.atencao{background:var(--laranja-c);color:#B35400}.ruim{background:var(--vermelho-c);color:var(--vermelho)}.neutro{background:var(--fundo);color:var(--suave)}
.barras{display:flex;align-items:flex-end;gap:4px;height:160px;padding-top:8px}
.barra{flex:1;display:flex;flex-direction:column;justify-content:flex-end;align-items:center;height:100%;min-width:0}
.barra i{display:block;width:100%;border-radius:6px 6px 0 0;background:linear-gradient(180deg,var(--laranja),var(--magenta));min-height:2px}
.barra span{font-size:10px;color:var(--suave);margin-top:4px;white-space:nowrap}
.lista{display:grid;gap:10px}.item{display:flex;gap:10px;justify-content:space-between;border-bottom:1px solid var(--linha);padding-bottom:8px}.item:last-child{border:0}
.vazio{color:var(--suave);font-size:14px}
.login{max-width:380px;margin:60px auto;background:#fff;border-radius:20px;padding:28px;text-align:center;box-shadow:0 2px 10px rgba(5,37,69,.1)}
.login img{width:180px}.login input{width:100%;padding:12px;border:2px solid var(--linha);border-radius:12px;font-size:16px;margin:8px 0}
button.excluir{background:#fff;color:var(--vermelho);border:1.5px solid var(--vermelho);padding:6px 12px;font-size:13px;border-radius:10px}
button.excluir:hover{background:var(--vermelho-c)}button.excluir:disabled{opacity:.6;cursor:wait}
.login button{width:100%;background:linear-gradient(90deg,var(--magenta),var(--laranja));color:#fff;padding:14px}.erro{color:var(--vermelho)}
`;

const JS_FROTA_ = `
const R=v=>(v||0).toLocaleString('pt-BR',{style:'currency',currency:'BRL'});
const N=v=>(v||0).toLocaleString('pt-BR');
const E=s=>String(s==null?'':s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const selo=(t,c)=>'<span class="selo '+c+'">'+E(t)+'</span>';
const cls={'Em dia':'ok','OK':'ok','Trocar em breve':'atencao','Vence em breve':'atencao','Abastecer':'ruim','Atrasada':'ruim','Vencido':'ruim'};
const k=D.kpis;
let h='<header><div class="topo"><div class="marca"><img src="${SIMBOLO_URL_}" alt="GCM" onerror="this.parentNode.remove()"></div><div><h1>Gestão de frota</h1><p>GCM Serviços · atualizado em '+E(D.atualizado)+'</p></div>'+
'<div class="acoes"><a class="btn" href="'+URL_FROTA+'" target="_top">Atualizar</a><a class="btn" href="'+E(D.planilha)+'" target="_blank">Abrir planilha</a></div></div></header><div class="faixa"></div><main>';
h+='<section class="kpis">'+
 '<div class="kpi"><small>Veículos</small><b>'+k.veiculos+'</b></div>'+
 '<div class="kpi '+(k.manutencao?'alerta':'')+'"><small>Em manutenção</small><b>'+k.manutencao+'</b></div>'+
 '<div class="kpi '+(k.abastecer?'alerta':'')+'"><small>Precisam abastecer</small><b>'+k.abastecer+'</b></div>'+
 '<div class="kpi '+(k.pendencias?'alerta':'')+'"><small>Óleo ou seguro pendente</small><b>'+k.pendencias+'</b></div>'+
 '<div class="kpi marca"><small>Gasto com combustível ('+E(D.mesNome)+')</small><b>'+R(k.gastoMes)+'</b></div>'+
 '<div class="kpi"><small>Km rodados no mês</small><b>'+N(k.kmMes)+' km</b></div>'+
 '<div class="kpi"><small>Litros no mês</small><b>'+N(k.litrosMes)+' L</b></div>'+
 '<div class="kpi"><small>Custo por km</small><b>'+R(k.custoKm)+'</b></div></section>';
h+='<section class="cartao"><h2>Veículos</h2><div class="tabela"><table><tr><th>Veículo</th><th>Técnico</th><th>Km atual</th><th>Combustível</th><th>Estado</th><th>Troca de óleo</th><th>Seguro obrigatório</th><th>Mês</th><th></th></tr>';
if(!D.frota.length)h+='<tr><td colspan="9" class="vazio">Nenhum veículo cadastrado.</td></tr>';
D.frota.forEach(f=>{
 const ch=f.ultimaChecagem;
 const est=f.emManutencao?selo('Em manutenção','ruim'):selo('Funcionando','ok');
 const oleo=f.oleo.situacao==='Sem registro'?selo('Sem registro','neutro'):selo(f.oleo.situacao,cls[f.oleo.situacao])+'<div class="sub">Próxima: '+N(f.oleo.proximaTrocaKm)+' km ('+(f.oleo.kmFaltando<0?'passou '+N(-f.oleo.kmFaltando):'faltam '+N(f.oleo.kmFaltando))+' km)</div>'+(f.oleo.agendadaPara?'<div class="sub"><b>Agendada para '+E(f.oleo.agendadaPara)+'</b></div>':'');
 const seg=f.seguro.situacao==='Sem registro'?selo('Sem registro','neutro'):selo(f.seguro.situacao,cls[f.seguro.situacao])+'<div class="sub">Vence '+E(f.seguro.vencimento)+(f.seguro.diasRestantes>=0?' ('+f.seguro.diasRestantes+' dias)':'')+'</div>';
 h+='<tr><td><b>'+E(f.placa)+'</b><div class="sub">'+E([f.modelo,f.cor,f.ano].filter(Boolean).join(' · '))+'</div></td>'+
 '<td>'+(E(f.tecnicos)||'<span class="sub">—</span>')+'</td><td>'+N(f.kmAtual)+' km</td>'+
 '<td>'+selo(f.situacao==='Sem abastecimento registrado'?'Sem registro':f.situacao,cls[f.situacao]||'neutro')+(f.situacao!=='Sem abastecimento registrado'?'<div class="sub">Restam '+N(f.kmRestantes)+' km</div>':'')+'</td>'+
 '<td>'+est+(ch?'<div class="sub">Checagem '+E(ch.data)+(ch.problema?': '+E(ch.problema):'')+'</div>':'<div class="sub">Sem checagem</div>')+'</td>'+
 '<td>'+oleo+'</td><td>'+seg+'</td><td>'+R(f.gastoMes)+'<div class="sub">'+N(f.kmMes)+' km</div></td>'+
 '<td><button class="excluir" data-placa="'+E(f.placa)+'" data-tecnicos="'+E(f.tecnicos)+'">Excluir</button></td></tr>';
});
h+='</table></div></section>';
const mx=Math.max(1,...D.dias.map(d=>d.gasto));
h+='<div class="duas"><section class="cartao"><h2>Gasto com combustível nos últimos 30 dias</h2><div class="barras">'+
 D.dias.map((d,i)=>'<div class="barra" title="'+E(d.dia)+': '+R(d.gasto)+'"><i style="height:'+(d.gasto/mx*100)+'%"></i><span>'+(i%5===4?E(d.dia):'&nbsp;')+'</span></div>').join('')+'</div></section>';
h+='<section class="cartao"><h2>Por técnico no mês</h2><div class="lista">'+(D.porTecnico.length?D.porTecnico.map(t=>'<div class="item"><div><b>'+E(t.nome)+'</b><div class="sub">'+E(t.placa)+' · '+t.abastecimentos+' abastecimento(s)</div></div><div style="text-align:right"><b>'+R(t.gastoMes)+'</b><div class="sub">'+N(t.kmMes)+' km</div></div></div>').join(''):'<p class="vazio">Nenhum técnico.</p>')+'</div></section></div>';
h+='<div class="duas"><section class="cartao"><h2>Últimos abastecimentos</h2><div class="tabela"><table><tr><th>Data</th><th>Técnico</th><th>Placa</th><th>Valor</th><th>Litros</th><th>Km</th><th>Nota</th></tr>'+
 (D.abastecimentos.length?D.abastecimentos.map(a=>'<tr><td>'+E(a.data)+'</td><td>'+E(a.tecnico)+'</td><td>'+E(a.placa)+'</td><td>'+R(a.valor)+'</td><td>'+N(a.litros)+'</td><td>'+N(a.km)+'</td><td>'+(a.nota?'<a href="'+E(a.nota)+'" target="_blank">Ver</a>':'')+'</td></tr>').join(''):'<tr><td colspan="7" class="vazio">Nenhum abastecimento registrado.</td></tr>')+'</table></div></section>';
h+='<section class="cartao"><h2>Ocorrências e alertas</h2><div class="lista">'+
 D.ocorrencias.map(o=>'<div class="item"><div>'+selo('Manutenção','ruim')+' <b>'+E(o.placa)+'</b><div class="sub">'+E(o.tecnico)+': '+E(o.problema||'sem descrição')+'</div></div><div class="sub">'+E(o.data)+'</div></div>').join('')+
 D.alertas.map(a=>'<div class="item"><div>'+selo(a.tipo,a.tipo==='Manutenção'?'ruim':'atencao')+' <b>'+E(a.placa)+'</b><div class="sub">'+E(a.tecnico)+'</div></div><div class="sub">'+E(a.data)+'</div></div>').join('')+
 (!D.ocorrencias.length&&!D.alertas.length?'<p class="vazio">Nenhuma ocorrência.</p>':'')+'</div></section></div>';
h+='<section class="cartao"><h2>Check-lists da semana</h2><div class="tabela"><table><tr><th>Técnico</th>'+['Segurança do veículo','Ferramental e EPI'].map(n=>'<th>'+n+'</th>').join('')+'</tr>'+
 (D.checklists.length?D.checklists.map(t=>'<tr><td><b>'+E(t.nome)+'</b><div class="sub">'+E(t.placa)+'</div></td>'+t.lista.map(c=>'<td>'+(c.feitoNaSemana?selo('Feito','ok'):selo('Pendente','atencao'))+
 (c.ultimoEm?'<div class="sub">Último: '+E(c.ultimoEm)+'</div>':'')+
 (c.problemas.length?'<div class="sub" style="color:var(--vermelho)">'+c.problemas.map(p=>E(p.item)+': '+E(p.condicao)+(p.observacao?' ('+E(p.observacao)+')':'')).join('<br>')+'</div>':'')+'</td>').join('')+'</tr>').join(''):'<tr><td colspan="3" class="vazio">Nenhum técnico.</td></tr>')+'</table></div></section>';
h+='<p class="sub" style="text-align:center">Para cadastrar troca de óleo, vencimento do seguro ou retirar a manutenção, use a aba Veículos da planilha. Os itens dos check-lists ficam na aba Itens dos check-lists.</p></main>';
document.getElementById('app').innerHTML=h;
document.querySelectorAll('button.excluir').forEach(b=>b.addEventListener('click',()=>{
 const placa=b.dataset.placa,tec=b.dataset.tecnicos;
 let msg='Deseja realmente excluir o veículo '+placa+'?'+' O histórico de abastecimentos, leituras e check-lists será mantido na planilha.';
 if(tec)msg+=' Atenção: o veículo está vinculado a '+tec+'. Ajuste a aba Técnicos depois.';
 if(!confirm(msg))return;
 b.disabled=true;b.textContent='Excluindo…';
 google.script.run.withSuccessHandler(()=>{window.top.location.href=URL_FROTA;})
  .withFailureHandler(e=>{b.disabled=false;b.textContent='Excluir';alert('Não foi possível excluir: '+(e&&e.message||e));})
  .excluirVeiculo(CHAVE,placa);
}));
`;

// ------------------------------------------------------------------ Versão web do técnico (iPhone)

/**
 * Página do técnico para iPhone (ou qualquer celular): <URL do app>?pagina=tecnico
 * Mesmo login (nome + senha de 4 números) e mesmo fluxo do aplicativo Android.
 */
function paginaTecnico_() {
  const cfg = { logo: LOGO_URL_, simbolo: SIMBOLO_URL_ };
  const html = '<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Combustível GCM</title>' +
    '<style>' + CSS_TECNICO_ + '</style></head><body><div id="app"></div>' +
    '<script>const CFG=' + JSON.stringify(cfg) + ';(' + appTecnico_.toString() + ')();</script></body></html>';
  const saida = HtmlService.createHtmlOutput(html).setTitle('Combustível GCM')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1, maximum-scale=1');
  try { saida.addMetaTag('apple-mobile-web-app-capable', 'yes'); } catch (err) { /* opcional */ }
  try { saida.addMetaTag('mobile-web-app-capable', 'yes'); } catch (err) { /* opcional */ }
  try { saida.setFaviconUrl(SIMBOLO_URL_); } catch (err) { /* opcional */ }
  return saida;
}

const CSS_TECNICO_ = `
:root{--marinho:#052545;--azul:#0E3A66;--magenta:#D1006C;--laranja:#FF7A12;--fundo:#F3F5F9;--texto:#14213D;--suave:#6B7588;
--linha:#E3E7EE;--verde:#1B9E5A;--verde-c:#E5F6EC;--vermelho:#D32F2F;--vermelho-c:#FDECEC;--laranja-c:#FFF1E6}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;background:var(--fundo);color:var(--texto);font-size:16px}
header{background:linear-gradient(135deg,var(--marinho),var(--azul));color:#fff;padding:16px;display:flex;align-items:center;gap:12px}
header .marca{background:#fff;border-radius:14px;width:46px;height:46px;display:flex;align-items:center;justify-content:center;flex:none}
header .marca img{width:34px}header h1{margin:0;font-size:20px}header p{margin:2px 0 0;opacity:.75;font-size:13px}
header .voltar{background:none;border:0;color:#fff;font-size:26px;padding:0 6px 0 0;cursor:pointer}
.faixa{height:4px;background:linear-gradient(90deg,var(--magenta),var(--laranja))}
main{max-width:560px;margin:0 auto;padding:16px 16px 40px;display:grid;gap:14px}
.cartao{background:#fff;border-radius:20px;padding:18px;box-shadow:0 1px 3px rgba(5,37,69,.08);display:grid;gap:12px}
.cartao h2{margin:0;font-size:18px}
.sub{color:var(--suave);font-size:14px;margin:0}
.btn{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;border:0;border-radius:16px;padding:16px;font-size:17px;font-weight:700;cursor:pointer;
background:linear-gradient(90deg,var(--magenta),var(--laranja));color:#fff;font-family:inherit}
.btn:disabled{background:#C9CFD9;color:#fff}
.btn.contorno{background:#fff;color:var(--texto);border:2px solid var(--linha)}
.btn.vermelho{background:var(--vermelho)}.btn.verde{background:var(--verde)}
.btn.pequeno{padding:10px 14px;font-size:15px;width:auto}
.linha{display:flex;gap:10px}.linha>*{flex:1}
.opcao{display:flex;align-items:center;gap:12px;width:100%;background:#fff;border:2px solid var(--linha);border-radius:14px;padding:16px;font-size:18px;font-weight:600;cursor:pointer;text-align:left;color:var(--texto);font-family:inherit}
.opcao.sel{background:var(--laranja-c);border-color:var(--laranja)}
input,textarea{width:100%;border:2px solid var(--linha);border-radius:14px;padding:14px;font-size:18px;font-weight:600;font-family:inherit;color:var(--texto);background:#fff}
input:focus,textarea:focus{outline:none;border-color:var(--magenta)}
label{font-size:14px;color:var(--suave);font-weight:600}
.pin{letter-spacing:12px;text-align:center;font-size:28px}
.aviso{border-radius:14px;padding:14px;font-weight:600}
.aviso.ok{background:var(--verde-c);color:var(--verde)}.aviso.erro{background:var(--vermelho-c);color:var(--vermelho)}.aviso.atencao{background:var(--laranja-c);color:#B35400}
.indicadores{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.ind{border-radius:14px;padding:14px;background:rgba(14,58,102,.07)}.ind small{color:var(--suave);font-size:13px}.ind b{display:block;font-size:24px;color:var(--azul)}
.ind.mg{background:rgba(209,0,108,.07)}.ind.mg b{color:var(--magenta)}
.etapa{display:flex;align-items:center;gap:14px;background:#fff;border:0;border-radius:20px;padding:16px;width:100%;box-shadow:0 1px 3px rgba(5,37,69,.08);cursor:pointer;text-align:left;font-family:inherit;color:var(--texto)}
.etapa .bola{width:52px;height:52px;border-radius:50%;background:linear-gradient(135deg,var(--magenta),var(--laranja));color:#fff;display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:800;flex:none}
.etapa.feito{background:var(--verde-c);box-shadow:none}.etapa.feito .bola{background:var(--verde)}
.etapa b{display:block;font-size:18px}.etapa span{font-size:14px;color:var(--suave)}.etapa.feito span{color:var(--verde)}
.foto{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;height:150px;border-radius:14px;background:var(--laranja-c);border:2px dashed rgba(255,122,18,.6);font-weight:700;cursor:pointer;color:var(--texto)}
.foto .cam{width:56px;height:56px;border-radius:50%;background:linear-gradient(135deg,var(--magenta),var(--laranja));display:flex;align-items:center;justify-content:center;color:#fff;font-size:26px}
.previa{width:100%;height:210px;object-fit:cover;border-radius:14px}
.item{border-bottom:1px solid var(--linha);padding-bottom:12px;display:grid;gap:8px}.item:last-child{border:0;padding:0}
.conds{display:flex;gap:6px}.conds button{flex:1;border:2px solid var(--linha);background:#fff;border-radius:12px;padding:10px 4px;font-size:14px;font-weight:700;cursor:pointer;font-family:inherit;color:var(--texto)}
.conds button.ok{background:var(--verde-c);border-color:var(--verde);color:var(--verde)}
.conds button.problema{background:var(--vermelho-c);border-color:var(--vermelho);color:var(--vermelho)}
.conds button.naotem{background:var(--laranja-c);border-color:var(--laranja);color:#B35400}
.carregando{height:4px;border-radius:4px;background:linear-gradient(90deg,var(--magenta),var(--laranja),var(--magenta));background-size:200% 100%;animation:c 1s linear infinite}
@keyframes c{to{background-position:-200% 0}}
.logo{display:block;width:220px;max-width:70%;margin:8px auto 0}
.rodape{display:flex;gap:10px;justify-content:space-between;align-items:center}
.link{background:none;border:0;color:var(--suave);font-size:15px;cursor:pointer;padding:8px;font-family:inherit}
`;

/** Código do navegador da página do técnico (enviado como texto; não roda no servidor). */
function appTecnico_() {
  const raiz = document.getElementById('app');
  const DIAS = ['Início do turno', 'Abastecimento', 'Fim do turno'];
  let sessao = null;
  let estado = {};

  function ler() { try { return JSON.parse(localStorage.getItem('gcm_sessao') || 'null'); } catch (e) { return null; } }
  function gravar(s) { try { if (s) localStorage.setItem('gcm_sessao', JSON.stringify(s)); else localStorage.removeItem('gcm_sessao'); } catch (e) { /* sem armazenamento */ } }
  function E(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
  function km(v) { return (Number(v) || 0).toLocaleString('pt-BR', { maximumFractionDigits: 0 }) + ' km'; }
  function reais(v) { return (Number(v) || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }); }
  function num(t) { const n = parseFloat(String(t || '').replace(/\./g, '').replace(',', '.')); return isNaN(n) ? null : n; }
  function hojeBr() { return new Date().toLocaleDateString('pt-BR'); }

  function chamar(fn, arg) {
    return new Promise((ok, falha) => {
      google.script.run.withSuccessHandler(t => {
        let r; try { r = JSON.parse(t); } catch (e) { falha(new Error('Resposta inválida do sistema.')); return; }
        if (!r.ok) {
          if (String(r.erro || '').indexOf('Entre novamente') === 0 && sessao) { sair('Sua senha mudou. Entre novamente.'); }
          falha(new Error(r.erro || 'Erro no sistema.')); return;
        }
        ok(r);
      }).withFailureHandler(e => falha(new Error('Sem conexão com o sistema. Verifique a internet e tente de novo.')))[fn](arg);
    });
  }
  function credenciais(extra) { return Object.assign({ tecnico: sessao.tecnico, senha: sessao.senha, placa: sessao.placa }, extra || {}); }

  function tela(titulo, subtitulo, corpo, voltar) {
    raiz.innerHTML = '<header>' + (voltar ? '<button class="voltar" data-acao="voltar" aria-label="Voltar">‹</button>' :
      '<div class="marca"><img src="' + E(CFG.simbolo) + '" alt="GCM" onerror="this.remove()"></div>') +
      '<div><h1>' + E(titulo) + '</h1><p>' + E(subtitulo) + '</p></div></header><div class="faixa"></div><main>' + corpo + '</main>';
    window.scrollTo(0, 0);
  }
  const ac = {};
  raiz.addEventListener('click', ev => {
    const b = ev.target.closest('[data-acao]');
    if (!b || b.disabled) return;
    const f = ac[b.dataset.acao];
    if (f) f(b);
  });

  // ---------------------------------------------------------------- Login
  function sair(msg) { gravar(null); sessao = null; telaLogin(msg); }

  function telaLogin(msg) {
    estado = { login: { tecnicos: null, escolhido: null } };
    tela('Combustível GCM', 'Entrar', '<img class="logo" src="' + E(CFG.logo) + '" alt="GCM Serviços" onerror="this.remove()">' +
      '<div class="cartao" id="login"><h2>Quem é você?</h2><div class="carregando"></div></div>' + (msg ? '<div class="aviso atencao">' + E(msg) + '</div>' : ''), false);
    chamar('webConsultar', { acao: 'tecnicos' }).then(r => { estado.login.tecnicos = r.tecnicos; desenharLogin(); })
      .catch(e => { document.getElementById('login').innerHTML = '<h2>Quem é você?</h2><div class="aviso erro">' + E(e.message) + '</div><button class="btn contorno" data-acao="recarregarLogin">Tentar de novo</button>'; });
  }
  ac.recarregarLogin = () => telaLogin();

  function desenharLogin(erro) {
    const L = estado.login;
    const t = L.escolhido;
    let h = '<h2>Quem é você?</h2>';
    if (!L.tecnicos.length) h += '<div class="aviso erro">Ainda não há técnicos cadastrados. Toque em Primeiro acesso abaixo.</div>';
    L.tecnicos.forEach((x, i) => { h += '<button class="opcao' + (t === x ? ' sel' : '') + '" data-acao="escolher" data-i="' + i + '">👤 ' + E(x.nome) + '</button>'; });
    if (t) {
      if (t.temSenha) {
        h += '<label for="senha">Sua senha (4 números)</label><input id="senha" class="pin" type="password" inputmode="numeric" maxlength="4" autocomplete="current-password">';
      } else {
        h += '<div class="aviso atencao">Primeiro acesso: crie uma senha de 4 números. Ela será pedida só ao entrar.</div>' +
          '<label for="senha">Nova senha (4 números)</label><input id="senha" class="pin" type="password" inputmode="numeric" maxlength="4" autocomplete="new-password">' +
          '<label for="senha2">Repita a senha</label><input id="senha2" class="pin" type="password" inputmode="numeric" maxlength="4" autocomplete="new-password">';
      }
      if (erro) h += '<div class="aviso erro">' + E(erro) + '</div>';
      h += '<button class="btn" data-acao="entrar">Entrar</button>';
    }
    h += '<button class="btn contorno" data-acao="cadastro">✍️ Primeiro acesso? Fazer meu cadastro</button>';
    document.getElementById('login').innerHTML = h;
    const s = document.getElementById('senha'); if (s) s.focus();
  }
  ac.escolher = b => { estado.login.escolhido = estado.login.tecnicos[Number(b.dataset.i)]; desenharLogin(); };
  ac.entrar = b => {
    const t = estado.login.escolhido;
    const senha = (document.getElementById('senha').value || '').trim();
    const s2 = document.getElementById('senha2');
    if (!/^[0-9]{4}$/.test(senha)) { desenharLogin('A senha deve ter 4 números.'); return; }
    if (s2 && s2.value.trim() !== senha) { desenharLogin('As senhas não são iguais.'); return; }
    b.disabled = true; b.textContent = 'Entrando...';
    chamar('webRegistrar', { tipo: 'login', tecnico: t.nome, senha }).then(r => {
      sessao = { tecnico: r.tecnico, senha, placa: r.placa || (r.placas.length === 1 ? r.placas[0] : '') };
      if (sessao.placa) { gravar(sessao); telaInicio(r.primeiroAcesso ? 'Senha criada. Bem-vindo!' : ''); }
      else telaVeiculo(r.placas);
    }).catch(e => desenharLogin(e.message));
  };

  // ---------------------------------------------------------------- Cadastro do técnico
  const CAMPOS_CAD = [['nome', 'Seu nome e sobrenome', 'text', 'words'], ['email', 'Seu e-mail (opcional)', 'email', 'none'],
    ['placa', 'Placa do veículo', 'text', 'characters'], ['modelo', 'Modelo do veículo', 'text', 'words'],
    ['ano', 'Ano do veículo', 'number', 'none'], ['cor', 'Cor do veículo', 'text', 'words']];
  ac.cadastro = () => { estado = { cad: {} }; desenharCadastro(); };
  function desenharCadastro(erro) {
    const c = estado.cad;
    let x = '<div class="cartao"><h2>Seus dados</h2><p class="sub">Preencha uma única vez. Depois, é só entrar com o nome e a senha.</p>';
    CAMPOS_CAD.forEach(f => {
      x += '<label for="cad_' + f[0] + '">' + f[1] + '</label><input id="cad_' + f[0] + '" type="' + (f[2] === 'number' ? 'text' : f[2]) + '"' +
        (f[2] === 'number' ? ' inputmode="numeric" maxlength="4"' : '') + ' autocapitalize="' + f[3] + '" value="' + E(c[f[0]] || '') + '">';
    });
    x += '</div><div class="cartao"><h2>Crie sua senha</h2><label for="cad_senha">Senha (4 números)</label><input id="cad_senha" class="pin" type="password" inputmode="numeric" maxlength="4" autocomplete="new-password">' +
      '<label for="cad_senha2">Repita a senha</label><input id="cad_senha2" class="pin" type="password" inputmode="numeric" maxlength="4" autocomplete="new-password"></div>' +
      (erro ? '<div class="aviso erro">' + E(erro) + '</div>' : '') + '<button class="btn" data-acao="salvarCadastro">Cadastrar e entrar</button>';
    tela('Primeiro acesso', 'Cadastro do técnico', x, true);
    ac.voltar = () => { ac.voltar = () => telaInicio(); telaLogin(); };
  }
  ac.salvarCadastro = b => {
    const c = estado.cad;
    CAMPOS_CAD.forEach(f => { c[f[0]] = (document.getElementById('cad_' + f[0]).value || '').trim(); });
    const senha = document.getElementById('cad_senha').value.trim();
    if (c.nome.split(/ +/).length < 2) return desenharCadastro('Digite seu nome e sobrenome.');
    if (!c.placa) return desenharCadastro('Digite a placa do veículo.');
    if (!/^[0-9]{4}$/.test(senha)) return desenharCadastro('A senha deve ter 4 números.');
    if (document.getElementById('cad_senha2').value.trim() !== senha) return desenharCadastro('As senhas não são iguais.');
    b.disabled = true; b.textContent = 'Cadastrando...';
    chamar('webRegistrar', Object.assign({ tipo: 'cadastro', senha }, c)).then(r => {
      sessao = { tecnico: r.tecnico, senha, placa: r.placa };
      gravar(sessao);
      ac.voltar = () => telaInicio();
      telaInicio('Cadastro feito. Bem-vindo, ' + r.tecnico.split(' ')[0] + '!');
    }).catch(e => desenharCadastro(e.message));
  };

  function telaVeiculo(placas) {
    estado.placas = placas;
    tela('Combustível GCM', sessao.tecnico, '<div class="cartao"><h2>Qual veículo você usa?</h2>' +
      (placas.length ? placas.map((p, i) => '<button class="opcao" data-acao="placa" data-i="' + i + '">🚗 ' + E(p) + '</button>').join('') :
        '<div class="aviso erro">Nenhum veículo cadastrado. Fale com o responsável.</div>') + '</div>', false);
  }
  ac.placa = b => { sessao.placa = estado.placas[Number(b.dataset.i)]; gravar(sessao); telaInicio(); };

  // ---------------------------------------------------------------- Início
  function telaInicio(msg) {
    estado = { msg: msg || '' };
    tela('Olá, ' + sessao.tecnico.split(' ')[0], 'Veículo ' + sessao.placa + ' · ' + hojeBr(), '<div id="conteudo"><div class="carregando"></div></div>', false);
    chamar('webConsultar', credenciais()).then(r => {
      estado.r = r;
      estado.veiculo = (r.veiculos || []).find(v => v.placa === sessao.placa) || null;
      desenharInicio();
    }).catch(e => {
      const c = document.getElementById('conteudo');
      if (c) c.innerHTML = '<div class="aviso erro">' + E(e.message) + '</div><button class="btn contorno" data-acao="atualizar">Tentar de novo</button>';
    });
  }
  ac.atualizar = () => telaInicio();
  ac.sair = () => { if (confirm('Sair e entrar com outro técnico?')) sair(); };

  function desenharInicio() {
    const h = estado.r.hoje || {};
    const v = estado.veiculo;
    let x = '';
    if (estado.msg) x += '<div class="aviso ok">' + E(estado.msg).replace(/\n/g, '<br>') + '</div>';
    if (h.emManutencao) {
      x += '<div class="cartao" style="background:var(--vermelho-c)"><h2 style="color:var(--vermelho)">🔧 Veículo em manutenção</h2>' +
        '<p class="sub">Quando o veículo voltar a funcionar, toque no botão abaixo.</p><button class="btn verde" data-acao="voltouFuncionar">O veículo voltou a funcionar</button></div>';
    } else if (!h.checagemFeita) {
      x += '<div class="cartao" id="checagem"><h2>O veículo está funcionando corretamente?</h2><div class="linha">' +
        '<button class="btn verde" data-acao="funciona">Sim</button><button class="btn contorno" data-acao="naoFunciona">Não</button></div></div>';
    }
    x += '<div class="cartao"><h2>Resumo de hoje</h2><div class="indicadores"><div class="ind"><small>Km rodados</small><b>' + km(h.kmRodados) +
      '</b></div><div class="ind mg"><small>Combustível gasto</small><b>' + reais(h.gasto) + '</b></div></div>' +
      '<p class="sub">' + (h.precoLitro > 0 ? km(h.kmRodados) + ' ÷ ' + (estado.r.config.consumoKmL || 10) + ' km/l = ' + (h.litros || 0).toLocaleString('pt-BR') + ' litros × ' + reais(h.precoLitro) + ' por litro'
        : 'O gasto aparece depois do primeiro abastecimento registrado.') + '</p></div>';
    if (v) {
      if (v.situacao === 'Abastecer') x += '<div class="aviso erro">⛽ Abasteça o veículo: restam ' + km(v.kmRestantes) + '.</div>';
      else if (v.situacao === 'OK') x += '<div class="aviso ok">⛽ Tanque: restam cerca de ' + km(v.kmRestantes) + '.</div>';
      const s = v.seguro || {};
      if (s.situacao === 'Vencido') x += '<div class="aviso erro">Seguro obrigatório vencido em ' + E(s.vencimento) + '.</div>';
      else if (s.situacao === 'Vence em breve') x += '<div class="aviso atencao">Seguro obrigatório vence em ' + E(s.vencimento) + ' (' + s.diasRestantes + ' dias).</div>';
      const o = v.oleo || {};
      if (o.situacao && o.situacao !== 'Sem registro') {
        x += '<div class="cartao"><h2>🛢️ Troca de óleo</h2><p class="sub">' + E(o.situacao) + ' · próxima em ' + km(o.proximaTrocaKm) +
          (o.kmFaltando < 0 ? ' (passou ' + km(-o.kmFaltando) + ')' : ' (faltam ' + km(o.kmFaltando) + ')') + '</p>' +
          (o.agendadaPara ? '<div class="aviso ok">Agendada para ' + E(o.agendadaPara) + '</div>' : '') +
          (o.situacao !== 'Em dia' || o.agendadaPara ? '<label for="dataOleo">' + (o.agendadaPara ? 'Mudar a data' : 'Agendar a troca de óleo') + '</label><input id="dataOleo" type="date" value="' + E(o.agendadaIso || '') + '"><button class="btn contorno" data-acao="agendarOleo">Salvar data</button>' : '') + '</div>';
      }
    }
    const pend = (estado.r.checklists || []).filter(c => !c.feitoNaSemana);
    if (pend.length) x += '<div class="aviso atencao">📋 Check-list da semana pendente: ' + E(pend.map(c => c.nome).join(' e ')) + '.</div>';
    x += '<h2 style="margin:6px 0 0;font-size:18px">Etapas do dia</h2>';
    const feitos = [h.inicioFeito, h.abastecimentoFeito, h.fimFeito];
    const desc = ['8h · foto do painel e km', 'Nota fiscal, valor, litros e km', '17h · foto do painel e km'];
    DIAS.forEach((d, i) => {
      x += '<button class="etapa' + (feitos[i] ? ' feito' : '') + '" data-acao="etapa" data-i="' + i + '"><div class="bola">' + (feitos[i] ? '✓' : (i + 1)) +
        '</div><div><b>' + (i + 1) + '. ' + d + '</b><span>' + (feitos[i] ? 'Concluído hoje' : desc[i]) + '</span></div></button>';
    });
    x += '<h2 style="margin:6px 0 0;font-size:18px">Check-lists da semana</h2>';
    (estado.r.checklists || []).forEach((c, i) => {
      x += '<button class="etapa' + (c.feitoNaSemana ? ' feito' : '') + '" data-acao="checklist" data-i="' + i + '"><div class="bola">' + (c.feitoNaSemana ? '✓' : '📋') +
        '</div><div><b>' + E(c.nome) + '</b><span>' + (c.feitoNaSemana ? 'Feito nesta semana' : 'Pendente nesta semana') + (c.ultimoEm ? ' · último ' + E(c.ultimoEm) : '') + '</span></div></button>';
    });
    let dica = false; try { dica = !localStorage.getItem('gcm_dica') && !window.navigator.standalone; } catch (e) { dica = false; }
    if (dica) x += '<div class="cartao"><h2>📲 Deixe na tela do celular</h2><p class="sub">No iPhone, abra este endereço no Safari, toque em Compartilhar e depois em "Adicionar à Tela de Início". Assim o app fica com ícone, igual a um aplicativo.</p><button class="btn contorno pequeno" data-acao="dicaOk">Entendi</button></div>';
    x += '<div class="rodape"><button class="btn contorno pequeno" data-acao="atualizar">↻ Atualizar</button><button class="link" data-acao="sair">Trocar técnico</button></div>';
    document.getElementById('conteudo').innerHTML = x;
  }
  ac.dicaOk = () => { try { localStorage.setItem('gcm_dica', '1'); } catch (e) { /* ok */ } desenharInicio(); };

  function enviarChecagem(funcionando, descricao, b) {
    b.disabled = true;
    return chamar('webRegistrar', credenciais({ tipo: 'checagem', funcionando, descricao: descricao || '' }))
      .then(() => telaInicio(funcionando ? 'Obrigado! Bom trabalho.' : 'Veículo marcado como em manutenção. O responsável foi avisado.'))
      .catch(e => { b.disabled = false; alert(e.message); });
  }
  ac.funciona = b => enviarChecagem(true, '', b);
  ac.voltouFuncionar = b => enviarChecagem(true, 'Voltou a funcionar', b);
  ac.naoFunciona = () => {
    document.getElementById('checagem').innerHTML = '<h2>O que aconteceu com o veículo?</h2><textarea id="problema" rows="3" placeholder="Descreva o problema (opcional)"></textarea>' +
      '<button class="btn vermelho" data-acao="manutencao">🔧 Veículo em manutenção</button><button class="link" data-acao="atualizar">Cancelar</button>';
  };
  ac.manutencao = b => enviarChecagem(false, document.getElementById('problema').value, b);
  ac.agendarOleo = b => {
    const d = document.getElementById('dataOleo').value;
    if (!d) { alert('Escolha a data.'); return; }
    b.disabled = true;
    chamar('webRegistrar', credenciais({ tipo: 'agendar_oleo', data: d })).then(() => telaInicio('Troca de óleo agendada.'))
      .catch(e => { b.disabled = false; alert(e.message); });
  };

  // ---------------------------------------------------------------- Registro (início, abastecimento, fim)
  function carimbar(arquivo, largura) {
    return new Promise((ok, falha) => {
      const url = URL.createObjectURL(arquivo);
      const img = new Image();
      img.onload = () => {
        const esc = Math.min(1, largura / Math.max(img.width, img.height));
        const c = document.createElement('canvas');
        c.width = Math.round(img.width * esc); c.height = Math.round(img.height * esc);
        const g = c.getContext('2d');
        g.drawImage(img, 0, 0, c.width, c.height);
        const t = new Date().toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) + '  ' + sessao.tecnico + '  ' + sessao.placa;
        const fs = Math.max(16, Math.round(c.width / 32));
        g.font = 'bold ' + fs + 'px sans-serif';
        g.fillStyle = 'rgba(0,0,0,.55)'; g.fillRect(0, c.height - fs * 1.8, c.width, fs * 1.8);
        g.fillStyle = '#fff'; g.fillText(t, fs * 0.6, c.height - fs * 0.6);
        URL.revokeObjectURL(url);
        ok(c.toDataURL('image/jpeg', 0.8));
      };
      img.onerror = () => falha(new Error('Não foi possível abrir a foto.'));
      img.src = url;
    });
  }

  function blocoFoto(id, rotulo, foto) {
    return '<input type="file" accept="image/*" capture="environment" id="arq_' + id + '" data-foto="' + id + '" hidden>' +
      (foto ? '<img class="previa" src="' + foto + '" alt=""><label class="btn contorno" for="arq_' + id + '">📷 Tirar outra foto</label>'
        : '<label class="foto" for="arq_' + id + '"><span class="cam">📷</span>' + E(rotulo) + '</label>');
  }

  function telaRegistro(i) {
    estado = { etapa: i, fotos: {}, valor: String((estado.r && estado.r.config && estado.r.config.valorPadrao) || 180).replace('.', ','), litros: '', km: '' };
    desenharRegistro();
  }
  ac.etapa = b => telaRegistro(Number(b.dataset.i));
  ac.voltar = () => telaInicio();

  function desenharRegistro(erro) {
    const s = estado; const abast = s.etapa === 1;
    let x = '';
    if (abast) {
      x += '<div class="cartao"><h2>1. Nota fiscal</h2>' + blocoFoto('nota', 'Fotografar a nota fiscal', s.fotos.nota);
      if (s.fotos.nota) x += '<label for="valor">Valor colocado (R$)</label><input id="valor" inputmode="decimal" value="' + E(s.valor) + '">' +
        '<label for="litros">Litros que couberam no tanque</label><input id="litros" inputmode="decimal" value="' + E(s.litros) + '">';
      x += '</div>';
    }
    x += '<div class="cartao"><h2>' + (abast ? '2. ' : '') + 'Km do painel</h2>' + blocoFoto('painel', 'Fotografar o painel', s.fotos.painel);
    if (s.fotos.painel) x += '<label for="km">Digite o km que aparece no painel</label><input id="km" inputmode="numeric" pattern="[0-9]*" value="' + E(s.km) + '">';
    x += '</div>';
    if (erro) x += '<div class="aviso erro">' + E(erro) + '</div>';
    x += '<button class="btn" data-acao="enviarRegistro" style="padding:20px">✓ ' + ['OK', 'Enviar abastecimento', 'Encerrar turno'][s.etapa] + '</button>';
    tela(DIAS[s.etapa], 'Etapa ' + (s.etapa + 1) + ' de 3 · ' + hojeBr(), x, true);
  }
  function guardarCampos() {
    ['valor', 'litros', 'km'].forEach(k => { const el = document.getElementById(k); if (el) estado[k] = el.value; });
  }
  raiz.addEventListener('change', ev => {
    const el = ev.target;
    if (!el.dataset || !el.dataset.foto || !el.files || !el.files[0]) return;
    guardarCampos();
    carimbar(el.files[0], el.dataset.foto === 'nota' ? 1600 : 1280)
      .then(d => { estado.fotos[el.dataset.foto] = d; desenharRegistro(); })
      .catch(e => desenharRegistro(e.message));
  });
  ac.enviarRegistro = b => {
    guardarCampos();
    const s = estado; const abast = s.etapa === 1;
    const kmN = parseInt(String(s.km).replace(/[^0-9]/g, ''), 10);
    if (abast && !s.fotos.nota) return desenharRegistro('Fotografe a nota fiscal.');
    if (!s.fotos.painel) return desenharRegistro('Fotografe o painel.');
    if (!(kmN > 0)) return desenharRegistro('Digite o km do painel.');
    const corpo = credenciais({ km: kmN, origem: 'Digitado (versão web)' });
    if (abast) {
      const v = num(s.valor), l = num(s.litros);
      if (!(v > 0)) return desenharRegistro('Digite o valor colocado.');
      if (!(l > 0)) return desenharRegistro('Digite os litros que couberam no tanque.');
      const consumo = (estado.r && estado.r.config && estado.r.config.consumoKmL) || 10;
      Object.assign(corpo, { tipo: 'abastecimento', valor: v, litros: l, kmCoube: Math.round(l * consumo),
        fotoBase64: s.fotos.nota.split(',')[1], fotoPainelBase64: s.fotos.painel.split(',')[1] });
    } else {
      Object.assign(corpo, { tipo: 'leitura', periodo: s.etapa === 0 ? 'Início do dia' : 'Fim do dia', fotoBase64: s.fotos.painel.split(',')[1] });
    }
    b.disabled = true; b.textContent = 'Enviando...';
    chamar('webRegistrar', corpo).then(r => {
      const partes = [['Início do turno registrado. Bom trabalho!', 'Abastecimento registrado.', 'Turno encerrado.'][s.etapa]];
      if (r.hoje && (s.etapa === 2 || r.hoje.kmRodados > 0)) partes.push('Hoje você rodou ' + km(r.hoje.kmRodados) + ' e gastou ' + reais(r.hoje.gasto) + ' de combustível.');
      if (r.situacao && r.situacao.situacao === 'Abastecer') partes.push('Atenção: o veículo precisa ser abastecido (restam ' + km(r.situacao.kmRestantes) + '). O responsável foi avisado.');
      telaInicio(partes.join('\n'));
    }).catch(e => desenharRegistro('Não foi possível enviar: ' + e.message));
  };

  // ---------------------------------------------------------------- Check-lists
  ac.checklist = b => {
    const c = estado.r.checklists[Number(b.dataset.i)];
    estado = { r: estado.r, lista: c, resp: c.itens.map(() => ({ condicao: '', observacao: '' })) };
    desenharChecklist();
  };
  function desenharChecklist(erro) {
    const c = estado.lista;
    let x = '<div class="cartao"><p class="sub">Marque a condição de cada item.</p><button class="btn contorno pequeno" data-acao="restantesOk">Marcar os restantes como OK</button></div><div class="cartao">';
    c.itens.forEach((it, i) => {
      const r = estado.resp[i];
      x += '<div class="item"><b>' + E(it) + '</b><div class="conds">' +
        [['OK', 'ok'], ['Com problema', 'problema'], ['Não tem', 'naotem']].map(p => '<button data-acao="cond" data-i="' + i + '" data-c="' + p[0] + '" class="' + (r.condicao === p[0] ? p[1] : '') + '">' + p[0] + '</button>').join('') + '</div>' +
        (r.condicao && r.condicao !== 'OK' ? '<input data-obs="' + i + '" placeholder="Observação (opcional)" value="' + E(r.observacao) + '">' : '') + '</div>';
    });
    x += '</div>' + (erro ? '<div class="aviso erro">' + E(erro) + '</div>' : '') + '<button class="btn" data-acao="enviarChecklist">Enviar check-list</button>';
    tela(c.nome, 'Check-list semanal · ' + hojeBr(), x, true);
  }
  function guardarObs() { raiz.querySelectorAll('[data-obs]').forEach(el => { estado.resp[Number(el.dataset.obs)].observacao = el.value; }); }
  ac.cond = b => { guardarObs(); estado.resp[Number(b.dataset.i)].condicao = b.dataset.c; const y = window.scrollY; desenharChecklist(); window.scrollTo(0, y); };
  ac.restantesOk = () => { guardarObs(); estado.resp.forEach(r => { if (!r.condicao) r.condicao = 'OK'; }); const y = window.scrollY; desenharChecklist(); window.scrollTo(0, y); };
  ac.enviarChecklist = b => {
    guardarObs();
    if (estado.resp.some(r => !r.condicao)) return desenharChecklist('Marque todos os itens.');
    b.disabled = true; b.textContent = 'Enviando...';
    const itens = estado.lista.itens.map((it, i) => ({ item: it, condicao: estado.resp[i].condicao, observacao: estado.resp[i].observacao }));
    chamar('webRegistrar', credenciais({ tipo: 'checklist', checklist: estado.lista.nome, itens }))
      .then(r => telaInicio('Check-list enviado.' + (r.problemas ? ' ' + r.problemas + ' item(ns) com problema; o responsável foi avisado.' : '')))
      .catch(e => desenharChecklist(e.message));
  };

  // ---------------------------------------------------------------- Começo
  sessao = ler();
  if (sessao && sessao.tecnico && sessao.senha && sessao.placa) telaInicio(); else telaLogin();
}
