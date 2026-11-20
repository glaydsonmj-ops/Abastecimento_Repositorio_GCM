/**
 * Controle de Abastecimento GCM Serviços - Google Apps Script
 *
 * Instalação (uma única vez):
 * 1. Abra a planilha "Controle de Abastecimento GCM Serviços".
 * 2. Menu Extensões > Apps Script. Apague o conteúdo e cole este arquivo.
 * 3. Defina a chave de acesso do app na aba Configurações (célula B8)
 *    ou, se preferir, na propriedade do script CHAVE_APP.
 * 4. Implantar > Nova implantação > Tipo "App da Web".
 *    Executar como: Eu. Quem pode acessar: Qualquer pessoa.
 * 5. Copie a URL gerada; ela será configurada no aplicativo.
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
  if (!chaveValida_(p.chave)) return json_({ ok: false, erro: 'Chave inválida' });
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
  return json_(resposta);
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
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const d = JSON.parse(e.postData.contents);
    if (!chaveValida_(d.chave)) return json_({ ok: false, erro: 'Chave inválida' });
    if (!d.placa || !d.tecnico) return json_({ ok: false, erro: 'Dados incompletos' });
    const ss = SpreadsheetApp.getActive();
    const agora = new Date();
    const cfg = parametros_();

    if (d.tipo === 'checagem') {
      const funcionando = d.funcionando === true || d.funcionando === 'true';
      ss.getSheetByName(ABA.CHECAGEM).appendRow([agora, d.tecnico, d.placa, funcionando ? 'Sim' : 'Não', d.descricao || '']);
      marcarManutencao_(d.placa, !funcionando);
      if (!funcionando) avisarManutencao_(d.placa, d.tecnico, d.descricao || '', agora, cfg);
      SpreadsheetApp.flush();
      return json_({ ok: true, hoje: resumoDia_(d.tecnico, d.placa, agora) });
    }

    if (d.tipo === 'checklist') {
      const nome = String(d.checklist || '');
      const itens = Array.isArray(d.itens) ? d.itens : [];
      if (!CHECKLISTS.includes(nome) || !itens.length) return json_({ ok: false, erro: 'Check-list incompleto' });
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
      return json_({ ok: true, problemas: problemas.length, checklists: situacaoChecklists_(d.tecnico, agora) });
    }

    if (d.tipo === 'agendar_oleo') {
      const m = String(d.data || '').match(/^(\d{4})-(\d{2})-(\d{2})$/);
      if (!m) return json_({ ok: false, erro: 'Data inválida' });
      const data = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12, 0, 0);
      const linha = linhaVeiculo_(d.placa);
      if (!linha) return json_({ ok: false, erro: 'Veículo não encontrado' });
      ss.getSheetByName(ABA.VEICULOS).getRange(linha, V.OLEO_AGENDADA + 1).setValue(data);
      SpreadsheetApp.flush();
      if (cfg.email) {
        MailApp.sendEmail(cfg.email, `Troca de óleo agendada: ${d.placa} em ${dataBr_(data)}`,
          `Prezado Glaydson,\n\nO técnico ${d.tecnico} agendou a troca de óleo do veículo ${d.placa} para ${dataBr_(data)}.\n\n` +
          `Atenciosamente,\nControle de Abastecimento GCM Serviços`);
      }
      const r = linhas_(ss.getSheetByName(ABA.VEICULOS)).find(l => l[V.PLACA] === d.placa);
      return json_({ ok: true, veiculo: veiculoJson_(r, cfg) });
    }

    const km = Number(d.km);
    if (!(km > 0)) return json_({ ok: false, erro: 'Informe o km' });

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
      return json_({ ok: true, relatorio: relatorio_(d.tecnico, d.placa, agora) });
    }

    const foto = d.fotoBase64 ? salvarFoto_(d.fotoBase64, d.placa, agora) : '';
    if (d.tipo === 'abastecimento') {
      const litros = Number(d.litros) || '';
      const kmCoube = Number(d.kmCoube) || (litros ? Math.round(litros * cfg.consumo) : '');
      if (!kmCoube) return json_({ ok: false, erro: 'Informe os km que couberam no tanque ou os litros' });
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
    return json_({ ok: true, situacao: status, alertaEnviado, kmDoDia: hoje.kmRodados, hoje });
  } catch (err) {
    return json_({ ok: false, erro: String(err) });
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
    corpo = `<div id="app"></div><script>const D=${dados};const URL_FROTA=${JSON.stringify(url + '?pagina=frota&chave=' + encodeURIComponent(chave))};</script><script>${JS_FROTA_}</script>`;
  }
  return HtmlService.createHtmlOutput(`<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Gestão de frota GCM</title><style>${CSS_FROTA_}</style></head><body>${corpo}</body></html>`)
    .setTitle('Gestão de frota GCM')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
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
h+='<section class="cartao"><h2>Veículos</h2><div class="tabela"><table><tr><th>Veículo</th><th>Técnico</th><th>Km atual</th><th>Combustível</th><th>Estado</th><th>Troca de óleo</th><th>Seguro obrigatório</th><th>Mês</th></tr>';
if(!D.frota.length)h+='<tr><td colspan="8" class="vazio">Nenhum veículo cadastrado.</td></tr>';
D.frota.forEach(f=>{
 const ch=f.ultimaChecagem;
 const est=f.emManutencao?selo('Em manutenção','ruim'):selo('Funcionando','ok');
 const oleo=f.oleo.situacao==='Sem registro'?selo('Sem registro','neutro'):selo(f.oleo.situacao,cls[f.oleo.situacao])+'<div class="sub">Próxima: '+N(f.oleo.proximaTrocaKm)+' km ('+(f.oleo.kmFaltando<0?'passou '+N(-f.oleo.kmFaltando):'faltam '+N(f.oleo.kmFaltando))+' km)</div>'+(f.oleo.agendadaPara?'<div class="sub"><b>Agendada para '+E(f.oleo.agendadaPara)+'</b></div>':'');
 const seg=f.seguro.situacao==='Sem registro'?selo('Sem registro','neutro'):selo(f.seguro.situacao,cls[f.seguro.situacao])+'<div class="sub">Vence '+E(f.seguro.vencimento)+(f.seguro.diasRestantes>=0?' ('+f.seguro.diasRestantes+' dias)':'')+'</div>';
 h+='<tr><td><b>'+E(f.placa)+'</b><div class="sub">'+E([f.modelo,f.cor,f.ano].filter(Boolean).join(' · '))+'</div></td>'+
 '<td>'+(E(f.tecnicos)||'<span class="sub">—</span>')+'</td><td>'+N(f.kmAtual)+' km</td>'+
 '<td>'+selo(f.situacao==='Sem abastecimento registrado'?'Sem registro':f.situacao,cls[f.situacao]||'neutro')+(f.situacao!=='Sem abastecimento registrado'?'<div class="sub">Restam '+N(f.kmRestantes)+' km</div>':'')+'</td>'+
 '<td>'+est+(ch?'<div class="sub">Checagem '+E(ch.data)+(ch.problema?': '+E(ch.problema):'')+'</div>':'<div class="sub">Sem checagem</div>')+'</td>'+
 '<td>'+oleo+'</td><td>'+seg+'</td><td>'+R(f.gastoMes)+'<div class="sub">'+N(f.kmMes)+' km</div></td></tr>';
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
`;
