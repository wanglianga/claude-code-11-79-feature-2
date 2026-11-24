<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { toast } from '../lib/store.js'

const loading = ref(true)
const error = ref('')
const domains = ref([])
const domain = ref('CN')
const observations = ref([])
const exposures = ref([])
const runs = ref([])
const detDetail = ref(null)
const prbDetail = ref(null)
const terminologies = ref([])
const aggregates = ref([])
const statsVersions = ref([])
const statsDetail = ref(null)
const acceptance = ref(null)
const strategy = ref('DETERMINISTIC')
const busy = ref(false)
const activeTab = ref('episodes')
// The safety scenario runs past the parent task's day-16 reference (recurrence on D20, the
// next dictionary edition on D28). It keeps its own analysis day rather than inheriting the
// workspace day, so switching screens never silently truncates the safety window.
const safetyDay = ref(26)

const detSummary = computed(() => pickRun('DETERMINISTIC'))
const prbSummary = computed(() => pickRun('PROBABILISTIC'))

// Prefer the run pinned on the active 25.1 edition; the acceptance screen's 26.0 re-run supersedes
// it for statistics purposes but is empty at day 26 (26.0 only becomes valid on day 28), so showing
// it by recency alone would misleadingly present zero episodes.
function pickRun(strategyName) {
  const sameStrategy = runs.value.filter((r) => r.strategy === strategyName)
  return sameStrategy.find((r) => (r.edition || '').includes('25.1')) || sameStrategy[0] || null
}

const mappedCount = computed(() => observations.value.filter((o) => o.mappedPreferredTerm).length)
const unmappedCount = computed(() => observations.value.filter((o) => o.mappingStatus === 'UNMAPPED').length)

const currentDetail = computed(() => (strategy.value === 'PROBABILISTIC' ? prbDetail.value : detDetail.value))
const rateCells = computed(() => statsDetail.value?.cells || [])

// the newest deterministic stats version under the active 25.1 edition is what the global team receives
const aggStatsCode = computed(() => {
  const target = statsVersions.value.find(
    (s) => s.strategy === 'DETERMINISTIC' && s.edition?.includes('25.1') && s.cellCount > 0
  )
  return target?.code || null
})

async function loadDomainReads() {
  const [obs, exp] = await Promise.all([
    api.get(`/safety/observations?domain=${domain.value}`),
    api.get(`/safety/exposures?domain=${domain.value}&day=${safetyDay.value}`)
  ])
  observations.value = obs
  exposures.value = exp
}

async function loadDetails() {
  const det = pickRun('DETERMINISTIC')
  const prb = pickRun('PROBABILISTIC')
  detDetail.value = det ? await api.get(`/safety/runs/${det.code}?domain=${domain.value}`) : null
  prbDetail.value = prb ? await api.get(`/safety/runs/${prb.code}?domain=${domain.value}`) : null
  const svTarget = statsVersions.value.find((s) => s.strategy === strategy.value && s.edition?.includes('25.1'))
    || statsVersions.value.find((s) => s.strategy === strategy.value)
  statsDetail.value = svTarget ? await api.get(`/safety/stats/${svTarget.code}`) : null
}

async function loadBase() {
  const [dm, rn, term, agg, sv] = await Promise.all([
    api.get('/safety/domains'),
    api.get('/safety/runs'),
    api.get('/safety/terminologies'),
    api.get('/safety/aggregates'),
    api.get('/safety/stats')
  ])
  domains.value = dm
  runs.value = rn
  terminologies.value = term
  aggregates.value = agg
  statsVersions.value = sv
  await loadDomainReads()
  await loadDetails()
}

async function reloadDomain() {
  await loadDomainReads()
  await loadDetails()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await loadBase()
    acceptance.value = await api.get(`/safety/acceptance?day=${safetyDay.value}`)
    await loadBase()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function switchStrategy(s) {
  strategy.value = s
  // prefer the active 25.1 edition so the rates table is never blanked by an empty 26.0 re-run
  const sameStrategy = statsVersions.value.filter((x) => x.strategy === s)
  const svTarget = sameStrategy.find((x) => (x.edition || '').includes('25.1')) || sameStrategy[0]
  if (svTarget) {
    statsDetail.value = await api.get(`/safety/stats/${svTarget.code}`)
  }
}

async function reconstruct(isProbabilistic) {
  busy.value = true
  try {
    const res = await api.post('/safety/reconstruct', {
      strategy: isProbabilistic ? 'PROBABILISTIC' : 'DETERMINISTIC',
      version: '25.1',
      day: safetyDay.value
    })
    toast(`${res.run.code}: ${res.episodeCount} episodes, ${res.candidateCount} candidates`, 'ok')
    await loadBase()
    await switchStrategy(isProbabilistic ? 'PROBABILISTIC' : 'DETERMINISTIC')
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function publishAggregate() {
  const target = aggStatsCode.value
  if (!target) {
    toast('No deterministic 25.1 stats version with cells to publish.', 'warn')
    return
  }
  busy.value = true
  try {
    const res = await api.post('/safety/aggregates/publish', {
      statsCode: target,
      approvalCode: 'DUA-GLOBAL-01',
      kFloor: 3
    })
    toast(`${res.code}: published ${res.publishedCells}, suppressed ${res.suppressedCells}`, 'ok')
    aggregates.value = await api.get('/safety/aggregates')
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function remap() {
  busy.value = true
  try {
    const res = await api.post('/safety/reconstruct', { strategy: 'DETERMINISTIC', version: '26.0', day: safetyDay.value })
    toast(`${res.run.code}: new stats version under 26.0 (supersedes prior; history untouched)`, 'ok')
    await loadBase()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

function fmt(dt) {
  if (!dt) return 'open'
  return new Date(dt).toISOString().replace('T', ' ').slice(0, 16)
}
function dayOf(dt) {
  if (!dt) return '—'
  const epoch = Date.UTC(2026, 2, 2, 9, 0, 0)
  const d = Math.floor((new Date(dt).getTime() - epoch) / 86400000) + 1
  return 'D' + d
}
function num(v, dp = 3) {
  if (v === null || v === undefined) return '—'
  return Number(v).toFixed(dp)
}
function recBadge(e) {
  return e.recurrence ? 'badge bad' : 'badge muted'
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Safety episodes</h1>
      <p class="lede">
        One clinical process can appear in a centre safety report, a hospital summary and a lab
        abnormality. Counting reports inflates the rate; merging on a wide window swallows a genuine
        recurrence. This screen reconstructs episode periods across sources, per participant and
        comparable term, and counts first and recurrent episodes against observable treatment days.
        Identity is resolved only inside the selected residence domain; the global side receives an
        aggregate with uncertainty intervals.
      </p>
      <div class="row small faint" style="margin-top: 8px; gap: 8px">
        <label>Analysis day
          <input type="number" min="1" max="365" style="width: 70px; margin-left: 6px"
                 v-model.number="safetyDay" @change="load" />
        </label>
        <span>·</span>
        <span>The safety scenario references day 26 (recurrence on D20; MedDRA 26.0 effective D28).</span>
      </div>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <div v-else-if="error" class="notice bad">{{ error }}</div>
    <template v-else>
      <!-- acceptance strip -->
      <section class="panel" style="margin-bottom: 16px">
        <div class="panel-head">
          <h2>Reconstruction checks</h2>
          <div class="spacer"></div>
          <span class="badge" :class="acceptance?.allPass ? 'ok' : 'bad'">
            {{ acceptance?.checks?.filter((c) => c.pass).length }}/{{ acceptance?.checks?.length }}
          </span>
        </div>
        <div class="panel-body">
          <div class="small muted" style="margin-bottom: 12px">{{ acceptance?.summary }}</div>
          <div class="grid cols-2">
            <div v-for="c in acceptance?.checks" :key="c.code" class="notice" :class="c.pass ? 'ok' : 'bad'">
              <div class="row" style="justify-content: space-between">
                <strong>{{ c.code }}</strong>
                <span class="badge" :class="c.pass ? 'ok' : 'bad'">{{ c.pass ? 'pass' : 'fail' }}</span>
              </div>
              <div class="small" style="margin: 6px 0">{{ c.requirement }}</div>
              <div class="mono-sm faint">expected: {{ c.expected }}</div>
              <div class="mono-sm">observed: {{ c.observed }}</div>
            </div>
          </div>
        </div>
      </section>

      <!-- controls -->
      <section class="panel" style="margin-bottom: 16px">
        <div class="panel-body row wrap" style="gap: 16px">
          <label class="field"><span>Residence domain</span>
            <select v-model="domain" @change="reloadDomain">
              <option v-for="d in domains" :key="d.domain" :value="d.domain">
                {{ d.domain }} — {{ d.observationCount }} obs / {{ d.participantCount }} participants
              </option>
            </select>
          </label>
          <div class="row" style="align-self: flex-end; gap: 8px">
            <button :disabled="busy" @click="reconstruct(false)">Reconstruct (deterministic)</button>
            <button :disabled="busy" @click="reconstruct(true)">Reconstruct (probabilistic)</button>
            <button class="ghost" :disabled="busy" @click="remap">Re-run under MedDRA 26.0</button>
          </div>
          <div class="spacer" style="flex: 1"></div>
          <div class="small faint">
            {{ mappedCount }} mapped · {{ unmappedCount }} unmapped ·
            exposure day {{ safetyDay }}
          </div>
        </div>
      </section>

      <!-- tabs -->
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'episodes' }" @click="activeTab = 'episodes'">Episodes</button>
        <button class="tab" :class="{ active: activeTab === 'observations' }" @click="activeTab = 'observations'">Source observations</button>
        <button class="tab" :class="{ active: activeTab === 'exposure' }" @click="activeTab = 'exposure'">Exposure &amp; rates</button>
        <button class="tab" :class="{ active: activeTab === 'global' }" @click="activeTab = 'global'">Global aggregate</button>
        <button class="tab" :class="{ active: activeTab === 'mapping' }" @click="activeTab = 'mapping'">Terminology</button>
      </div>

      <!-- EPISODES -->
      <template v-if="activeTab === 'episodes'">
        <div class="grid cols-2" style="margin-bottom: 16px">
          <section class="panel">
            <div class="panel-head">
              <h2>Deterministic</h2><div class="spacer"></div>
              <span class="small faint">{{ detSummary?.code || 'not run' }}</span>
            </div>
            <div class="panel-body small">
              Same participant + same preferred term + explicit time overlap only. Easy to audit, but a
              delayed supplementary report whose time stamp does not overlap is split into two episodes.
              <div class="row wrap" style="margin-top: 8px; gap: 6px">
                <span class="chip">episodes {{ detSummary?.episodeCount ?? '—' }}</span>
                <span class="chip">candidates {{ detSummary?.candidateCount ?? '—' }}</span>
              </div>
            </div>
          </section>
          <section class="panel">
            <div class="panel-head">
              <h2>Probabilistic</h2><div class="spacer"></div>
              <span class="small faint">{{ prbSummary?.code || 'not run' }}</span>
            </div>
            <div class="panel-body small">
              Scores on term similarity (PT/HLT/SOC), time proximity, resolution and treatment
              interruption. Pairs inside the ambiguity band are kept as candidates and never
              force-merged. Depends on calibration against a reviewed sample.
              <div class="row wrap" style="margin-top: 8px; gap: 6px">
                <span class="chip">episodes {{ prbSummary?.episodeCount ?? '—' }}</span>
                <span class="chip">candidates {{ prbSummary?.candidateCount ?? '—' }}</span>
              </div>
            </div>
          </section>
        </div>

        <section class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2>Episodes — {{ domain }}</h2>
            <div class="spacer"></div>
            <div class="row" style="gap: 6px">
              <button class="sm" :class="{ primary: strategy === 'DETERMINISTIC' }" @click="switchStrategy('DETERMINISTIC')">Deterministic</button>
              <button class="sm" :class="{ primary: strategy === 'PROBABILISTIC' }" @click="switchStrategy('PROBABILISTIC')">Probabilistic</button>
            </div>
          </div>
          <div class="panel-body tight">
            <div v-if="!currentDetail?.episodes?.length" class="empty">No episodes for this domain yet.</div>
            <table v-else>
              <thead>
                <tr><th>Episode</th><th>Participant</th><th>Term / SOC</th><th>Index</th><th>Range</th><th>Members</th><th>Evidence</th></tr>
              </thead>
              <tbody>
                <tr v-for="e in currentDetail.episodes" :key="e.episodeCode">
                  <td class="mono-sm">{{ e.episodeCode }}</td>
                  <td class="mono">{{ e.participantKey }}</td>
                  <td>{{ e.preferredTerm }}<div class="mono-sm faint">{{ e.soc }}</div></td>
                  <td>
                    <span class="badge" :class="recBadge(e)">{{ e.recurrence ? 'recurrence #' + e.occurrenceIndex : 'index #' + e.occurrenceIndex }}</span>
                    <div class="mono-sm faint">grade {{ e.maxGrade }}<span v-if="e.serious"> · serious</span></div>
                  </td>
                  <td class="mono-sm">{{ dayOf(e.startAt) }} → {{ e.endAt ? dayOf(e.endAt) : 'open' }}</td>
                  <td>
                    <div class="reason-list">
                      <span v-for="m in e.memberKeys" :key="m" class="chip">{{ m }}</span>
                    </div>
                  </td>
                  <td class="small">
                    <span v-if="e.resolutionEvidence" class="badge ok">resolved</span>
                    <span v-if="e.treatmentInterruption" class="badge warn">interruption</span>
                    <div class="mono-sm faint">{{ e.sourceTypes?.join(', ') }}</div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h2>Candidate edges &amp; reasons</h2><div class="spacer"></div>
            <span class="small faint">{{ strategy }}</span>
          </div>
          <div class="panel-body tight">
            <div v-if="!currentDetail?.edges?.length" class="empty">No edges.</div>
            <table v-else>
              <thead><tr><th>Kind</th><th>Score</th><th>Applied</th><th>Pair</th><th>Reasons</th></tr></thead>
              <tbody>
                <tr v-for="(x, i) in currentDetail.edges" :key="i">
                  <td>
                    <span class="badge" :class="x.kind === 'CANDIDATE_AMBIGUOUS' ? 'pend' : (x.applied ? 'ok' : 'warn')">
                      {{ x.kind }}
                    </span>
                  </td>
                  <td class="num">{{ num(x.score) }}</td>
                  <td>{{ x.applied ? 'yes' : 'no' }}</td>
                  <td class="mono-sm">{{ x.leftKey }} ↔ {{ x.rightKey }}</td>
                  <td><div class="reason-list"><span v-for="r in x.reasons" :key="r" class="chip">{{ r }}</span></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>

      <!-- OBSERVATIONS -->
      <template v-if="activeTab === 'observations'">
        <section class="panel">
          <div class="panel-head"><h2>Source observations — {{ domain }}</h2></div>
          <div class="panel-body tight">
            <table>
              <thead>
                <tr><th>Report</th><th>Participant</th><th>Source</th><th>Verbatim → PT</th><th>Window</th><th>Grade</th><th>Flags</th></tr>
              </thead>
              <tbody>
                <tr v-for="o in observations" :key="o.id + '-' + o.revision">
                  <td class="mono-sm">{{ o.sourceReportRef }}<div class="faint">{{ o.groupKey }}#{{ o.revision }}</div></td>
                  <td class="mono">{{ o.participantKey }}</td>
                  <td class="small">{{ o.sourceType }}<div class="mono-sm faint">{{ o.sourceSystem }}</div></td>
                  <td class="small">
                    {{ o.verbatimTerm }} → <strong>{{ o.mappedPreferredTerm || '—' }}</strong>
                    <div class="mono-sm faint">{{ o.codingSystem }} {{ o.codingVersion }} · {{ o.mappingStatus }}</div>
                  </td>
                  <td class="mono-sm">{{ dayOf(o.occurredStart) }} → {{ o.occurredEnd ? dayOf(o.occurredEnd) : 'open' }}</td>
                  <td class="num">{{ o.severityGrade }}</td>
                  <td class="small">
                    <span v-if="o.serious" class="badge bad">serious</span>
                    <span v-if="o.resolved" class="badge ok">resolved</span>
                    <span v-if="o.treatmentInterrupted" class="badge warn">interruption</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>

      <!-- EXPOSURE & RATES -->
      <template v-if="activeTab === 'exposure'">
        <section class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2>Exposure denominator — observable treatment days</h2>
            <div class="spacer"></div>
            <span class="chip">
              total {{ exposures.reduce((s, e) => s + e.observableDays, 0) }} days
            </span>
          </div>
          <div class="panel-body tight">
            <table>
              <thead><tr><th>Participant</th><th>Site</th><th>Window (days)</th><th>Unobservable</th><th>Observable (≤ day {{ safetyDay }})</th></tr></thead>
              <tbody>
                <tr v-for="e in exposures" :key="e.participantKey + e.startDay">
                  <td class="mono">{{ e.participantKey }}</td>
                  <td class="mono-sm">{{ e.siteCode }}</td>
                  <td class="num">D{{ e.startDay }}..D{{ e.endDay }}</td>
                  <td class="num">{{ e.unobservableDays }}</td>
                  <td class="num"><strong>{{ e.observableDays }}</strong></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head">
            <h2>First &amp; recurrent episode rates per 100 observable person-days</h2>
            <div class="spacer"></div>
            <div class="row" style="gap: 6px">
              <button class="sm" :class="{ primary: strategy === 'DETERMINISTIC' }" @click="switchStrategy('DETERMINISTIC')">Deterministic</button>
              <button class="sm" :class="{ primary: strategy === 'PROBABILISTIC' }" @click="switchStrategy('PROBABILISTIC')">Probabilistic</button>
            </div>
          </div>
          <div class="panel-body tight">
            <table>
              <thead>
                <tr><th>Preferred term</th><th>SOC</th><th>Index</th><th>Recurrence</th><th>Subjects</th><th>Exposure days</th><th>Index rate [95% band]</th><th>Recurrence rate [95% band]</th></tr>
              </thead>
              <tbody>
                <tr v-for="c in rateCells" :key="c.preferredTerm">
                  <td>{{ c.preferredTerm }}</td>
                  <td class="small muted">{{ c.soc }}</td>
                  <td class="num">{{ c.indexCount }}</td>
                  <td class="num">{{ c.recurrenceCount }}</td>
                  <td class="num">{{ c.subjectCount }}</td>
                  <td class="num">{{ c.exposureDays }}</td>
                  <td class="num">{{ c.indexRate.toFixed(3) }} <span class="faint">[{{ c.indexLower.toFixed(2) }}, {{ c.indexUpper.toFixed(2) }}]</span></td>
                  <td class="num">{{ c.recurrenceRate.toFixed(3) }} <span class="faint">[{{ c.recurrenceLower.toFixed(2) }}, {{ c.recurrenceUpper.toFixed(2) }}]</span></td>
                </tr>
                <tr v-if="!rateCells.length"><td colspan="8" class="empty">No computed rates.</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>

      <!-- GLOBAL AGGREGATE -->
      <template v-if="activeTab === 'global'">
        <section class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2>Global aggregate</h2><div class="spacer"></div>
            <button class="sm primary" :disabled="busy" @click="publishAggregate">Publish under DUA-GLOBAL-01 (k=3)</button>
          </div>
          <div class="panel-body small muted">
            The only safety artifact the global plane receives. Terms whose contributing participants
            fall below k are suppressed; only their aggregate is reported. No participant key or local
            pseudonym ever crosses the boundary.
          </div>
        </section>

        <section v-for="a in aggregates.slice(0, 3)" :key="a.code" class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2 class="mono">{{ a.code }}</h2><div class="spacer"></div>
            <span class="chip">k={{ a.kFloor }}</span>
            <span class="chip">published {{ a.publishedCells }}</span>
            <span class="chip">suppressed {{ a.suppressedCells }}</span>
          </div>
          <div class="panel-body">
            <div class="kv small" style="margin-bottom: 12px">
              <dt>Stats version</dt><dd class="mono">{{ a.statsVersion }} ({{ a.strategy }}, {{ a.edition }})</dd>
              <dt>Approval</dt><dd class="mono">{{ a.approvalCode }}</dd>
              <dt>Sites</dt><dd>{{ a.sites?.join(', ') }}</dd>
              <dt>Total exposure days</dt><dd class="num">{{ a.totalExposureDays }}</dd>
              <dt>Digest</dt><dd class="mono-sm faint">{{ a.digest?.slice(0, 32) }}…</dd>
            </div>
            <table v-if="a.cells?.length">
              <thead><tr><th>Term</th><th>Index</th><th>Recurrence</th><th>Subjects</th><th>Index rate [band]</th></tr></thead>
              <tbody>
                <tr v-for="c in a.cells" :key="c.preferredTerm">
                  <td>{{ c.preferredTerm }}</td>
                  <td class="num">{{ c.indexCount }}</td>
                  <td class="num">{{ c.recurrenceCount }}</td>
                  <td class="num">{{ c.subjectCount }}</td>
                  <td class="num">{{ c.indexRate.toFixed(3) }} <span class="faint">[{{ c.indexLower.toFixed(2) }}, {{ c.indexUpper.toFixed(2) }}]</span></td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty">No cell clears the k-anonymity floor; all are suppressed.</div>
          </div>
        </section>
        <div v-if="!aggregates.length" class="empty">No aggregate published yet.</div>
      </template>

      <!-- TERMINOLOGY -->
      <template v-if="activeTab === 'mapping'">
        <section v-for="t in terminologies" :key="t.codingSystem + t.version" class="panel" style="margin-bottom: 16px">
          <div class="panel-head">
            <h2>{{ t.codingSystem }} {{ t.version }}</h2><div class="spacer"></div>
            <span class="badge" :class="t.status === 'ACTIVE' ? 'ok' : 'muted'">{{ t.status }}</span>
            <span class="chip">{{ t.mappingCount }} terms</span>
          </div>
          <div class="panel-body small faint">valid {{ fmt(t.validFrom) }} → {{ t.validTo ? fmt(t.validTo) : 'open' }}</div>
          <div class="panel-body tight">
            <table>
              <thead><tr><th>Verbatim</th><th>Preferred term</th><th>HLT</th><th>SOC</th><th>Valid from</th></tr></thead>
              <tbody>
                <tr v-for="m in t.terms" :key="m.sourceTerm">
                  <td class="mono-sm">{{ m.sourceTerm }}</td>
                  <td>{{ m.preferredTerm }}</td>
                  <td class="small muted">{{ m.hlt }}</td>
                  <td class="small">{{ m.soc }}</td>
                  <td class="mono-sm faint">{{ fmt(m.validFrom) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </template>
  </div>
</template>
