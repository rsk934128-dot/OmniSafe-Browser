const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot list or write synced_links, session_monitors, or session_rounds", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("synced_links").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1").set({
      userId: ALICE_UID,
      title: "Wikipedia",
      url: "https://en.wikipedia.org",
      kind: "bookmark",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("session_monitors").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("session_rounds").get());
});

test("Authenticated owner: can create, list, update, and delete valid synced link", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const ref = aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_wiki");
  await assertSucceeds(
    ref.set({
      userId: ALICE_UID,
      title: "Wikipedia",
      url: "https://en.wikipedia.org",
      kind: "bookmark",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").get());
  await assertSucceeds(
    ref.update({
      title: "Wikipedia Home App",
      kind: "home_app",
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(ref.delete());

  const histRef = aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("hist_1");
  await assertSucceeds(
    histRef.set({
      userId: ALICE_UID,
      title: "Tor Project",
      url: "https://www.torproject.org",
      kind: "history",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("bad_kind").set({
      userId: ALICE_UID,
      title: "Invalid",
      url: "https://example.org",
      kind: "password",
      createdAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(histRef.delete());
});

test("Cross-user isolation: Bob cannot read or overwrite Alice's synced links", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const aliceRef = aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1");
  await assertSucceeds(
    aliceRef.set({
      userId: ALICE_UID,
      title: "DuckDuckGo",
      url: "https://duckduckgo.com",
      kind: "home_app",
      createdAt: now,
      updatedAt: now,
    })
  );

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_links").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("link_1").delete());
});

test("Shadow update & schema validation: rejects ghost fields and invalid schemes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("bad_ghost").set({
      userId: ALICE_UID,
      title: "Test",
      url: "https://example.org",
      kind: "bookmark",
      isVerified: true,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("synced_links").doc("bad_scheme").set({
      userId: ALICE_UID,
      title: "XSS",
      url: "javascript:alert(1)",
      kind: "bookmark",
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Session Monitor & Rounds: owner CRUD succeeds, cross-user and invalid payloads fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();

  const monitorRef = aliceDb.collection("users").doc(ALICE_UID).collection("session_monitors").doc("active_session");
  await assertSucceeds(
    monitorRef.set({
      userId: ALICE_UID,
      siteHost: "example.org",
      guardEnabled: true,
      alertLevel: "SAFE",
      elapsedMinutes: 12,
      timeLimitMinutes: 30,
      budgetLimit: 100.0,
      currencyLabel: "BDT",
      totalRounds: 4,
      winsCount: 2,
      totalWagered: 20.0,
      totalReturned: 28.5,
      netProfit: 8.5,
      averageCrashMultiplier: 2.35,
      maxCrashMultiplier: 5.71,
      isMonitoringActive: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    monitorRef.update({
      elapsedMinutes: 15,
      alertLevel: "APPROACHING_LIMIT",
      updatedAt: new Date(),
    })
  );

  // Reject mutating immutable userId on update
  await assertFails(
    monitorRef.update({
      userId: BOB_UID,
      updatedAt: new Date(),
    })
  );

  // Reject invalid alertLevel and winsCount > totalRounds
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("session_monitors").doc("bad_mon").set({
      userId: ALICE_UID,
      siteHost: "example.org",
      guardEnabled: true,
      alertLevel: "PREDICTION",
      elapsedMinutes: 0,
      timeLimitMinutes: 30,
      budgetLimit: 100.0,
      currencyLabel: "BDT",
      totalRounds: 1,
      winsCount: 5,
      totalWagered: 10.0,
      totalReturned: 0.0,
      netProfit: -10.0,
      averageCrashMultiplier: 1.2,
      maxCrashMultiplier: 1.2,
      isMonitoringActive: true,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Cross-user isolation on session_monitors
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("session_monitors").doc("active_session").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("session_monitors").get());

  // Session rounds CRUD & validation
  const roundRef = aliceDb.collection("users").doc(ALICE_UID).collection("session_rounds").doc("rnd_101");
  await assertSucceeds(
    roundRef.set({
      userId: ALICE_UID,
      siteHost: "example.org",
      betAmount: 10.0,
      crashMultiplier: 5.71,
      cashoutMultiplier: 2.0,
      netProfit: 10.0,
      note: "Logged test",
      clientTimestampMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("session_rounds").doc("rnd_101").get());
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("session_rounds").doc("rnd_bad").set({
      userId: ALICE_UID,
      siteHost: "example.org",
      betAmount: -5.0,
      crashMultiplier: 0.5,
      cashoutMultiplier: 0.0,
      netProfit: 0.0,
      note: "",
      clientTimestampMs: 1700000000000,
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(roundRef.delete());
});
