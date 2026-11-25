import { createClient } from "npm:@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

function serviceKey() {
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  const keys = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (keys) {
    const parsed = JSON.parse(keys);
    const value = parsed.default;
    if (value) return Deno.env.get(value) || value;
  }
  throw new Error("Chave administrativa do Supabase não configurada.");
}

const ASAAS_BASE = "https://api.asaas.com/v3";
async function asaas(path: string, apiKey: string, init: RequestInit = {}) {
  const response = await fetch(ASAAS_BASE + path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json",
      "access_token": apiKey,
      "User-Agent": "DORAMAX-GRATIS/1.0",
      ...(init.headers || {}),
    },
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error("Asaas API error", response.status, data);
    throw new Error(data?.errors?.[0]?.description || data?.message || "Falha na comunicação com o Asaas.");
  }
  return data;
}

async function issueAccess(admin: any, userId: string) {
  const { error } = await admin.from("access_grants").upsert(
    { user_id: userId, is_unlocked: true, updated_at: new Date().toISOString() },
    { onConflict: "user_id" },
  );
  if (error) throw error;
}

async function ensureWebhook(apiKey: string, token: string, email: string) {
  if (token.length < 32 || token.length > 255 || /\s/.test(token)) {
    throw new Error("ASAAS_WEBHOOK_TOKEN precisa ter entre 32 e 255 caracteres e não conter espaços.");
  }
  const webhookUrl = Deno.env.get("SUPABASE_URL") + "/functions/v1/asaas-webhook";
  const current = await asaas("/webhooks", apiKey);
  const rows = Array.isArray(current?.data) ? current.data : [];
  const existing = rows.find((item: any) => item.url === webhookUrl);
  const config = {
    name: "DORAMAX - Confirmação de pagamentos",
    url: webhookUrl,
    email,
    enabled: true,
    interrupted: false,
    apiVersion: 3,
    authToken: token,
    sendType: "SEQUENTIALLY",
    events: ["PAYMENT_RECEIVED", "CHECKOUT_PAID"],
  };
  if (existing?.id) {
    await asaas("/webhooks/" + encodeURIComponent(existing.id), apiKey, {
      method: "PUT",
      body: JSON.stringify(config),
    });
    return;
  }
  await asaas("/webhooks", apiKey, { method: "POST", body: JSON.stringify(config) });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Método não permitido." }, 405);

  try {
    const auth = req.headers.get("Authorization") || "";
    const jwt = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!jwt) return json({ error: "Faça login para continuar." }, 401);

    const url = Deno.env.get("SUPABASE_URL");
    const admin = createClient(url!, serviceKey(), { auth: { persistSession: false, autoRefreshToken: false } });
    const { data: authData, error: authError } = await admin.auth.getUser(jwt);
    const user = authData?.user;
    if (authError || !user) return json({ error: "Sessão inválida. Entre novamente." }, 401);

    const apiKey = Deno.env.get("ASAAS_API_KEY");
    if (!apiKey) return json({ error: "A chave ASAAS_API_KEY não está configurada no Supabase." }, 500);

    const body = await req.json().catch(() => ({}));
    const action = body?.action;

    const { data: grant, error: grantError } = await admin
      .from("access_grants").select("is_unlocked").eq("user_id", user.id).maybeSingle();
    if (grantError) throw grantError;
    if (grant?.is_unlocked === true) return json({ unlocked: true });

    if (action === "create") {
      const webhookToken = Deno.env.get("ASAAS_WEBHOOK_TOKEN");
      if (webhookToken) {
        try { await ensureWebhook(apiKey, webhookToken, user.email || ""); }
        catch (webhookError) { console.error("Não foi possível atualizar o webhook Asaas:", webhookError); }
      }

      // Reaproveita um Checkout pendente ainda dentro da validade, sem solicitar CPF no aplicativo.
      const { data: pending, error: pendingError } = await admin
        .from("doramax_payments")
        .select("asaas_payment_id,status,created_at")
        .eq("user_id", user.id).eq("status", "PENDING")
        .order("created_at", { ascending: false }).limit(5);
      if (pendingError) throw pendingError;
      const pendingCheckout = (pending || []).find((row: any) =>
        /^[0-9a-f]{8}-[0-9a-f-]{27}$/i.test(String(row.asaas_payment_id || ""))
      );
      if (pendingCheckout) {
        const checkoutUrl = "https://asaas.com/checkoutSession/show?id=" +
          encodeURIComponent(pendingCheckout.asaas_payment_id);
        return json({ paymentId: pendingCheckout.asaas_payment_id, checkoutUrl, invoiceUrl: checkoutUrl, unlocked: false });
      }

      const checkout = await asaas("/checkouts", apiKey, {
        method: "POST",
        body: JSON.stringify({
          billingTypes: ["PIX"],
          chargeTypes: ["DETACHED"],
          minutesToExpire: 60,
          externalReference: user.id,
          callback: {
            successUrl: "https://asaas.com",
            cancelUrl: "https://asaas.com",
            expiredUrl: "https://asaas.com",
          },
          items: [{
            name: "DORAMAX - Acesso completo",
            description: "Pagamento único para liberar todos os vídeos",
            quantity: 1,
            value: 5.00,
          }],
        }),
      });
      if (!checkout?.id) throw new Error("O Asaas não retornou o identificador do Checkout.");
      const checkoutUrl = checkout.link ||
        ("https://asaas.com/checkoutSession/show?id=" + encodeURIComponent(checkout.id));
      const { error: insertError } = await admin.from("doramax_payments").insert({
        user_id: user.id,
        asaas_payment_id: checkout.id,
        amount: 5.00,
        status: "PENDING",
        invoice_url: checkoutUrl,
      });
      if (insertError) throw insertError;
      return json({
        paymentId: checkout.id,
        checkoutUrl,
        invoiceUrl: checkoutUrl,
        unlocked: false,
      });
    }

    if (action === "status") {
      const paymentId = String(body?.paymentId || "");
      if (!paymentId || paymentId.length > 100) return json({ error: "Checkout inválido." }, 400);
      const { data: row, error: rowError } = await admin.from("doramax_payments")
        .select("id,user_id,asaas_payment_id,amount,status")
        .eq("asaas_payment_id", paymentId).eq("user_id", user.id).maybeSingle();
      if (rowError) throw rowError;
      if (!row) return json({ error: "Checkout não encontrado para esta conta." }, 404);

      // Confirmação real: consulta as cobranças vinculadas ao Checkout e valida valor, conta e status.
      const payments = await asaas("/payments?checkoutSession=" + encodeURIComponent(paymentId) + "&limit=100", apiKey);
      const paidPayment = (Array.isArray(payments?.data) ? payments.data : []).find((item: any) =>
        item.externalReference === user.id &&
        Number(item.value) === 5 &&
        item.billingType === "PIX" &&
        String(item.status) === "RECEIVED"
      );
      if (paidPayment) {
        const now = new Date().toISOString();
        const { error: updateError } = await admin.from("doramax_payments")
          .update({ status: "RECEIVED", paid_at: now, updated_at: now }).eq("id", row.id);
        if (updateError) throw updateError;
        await issueAccess(admin, user.id);
        return json({ unlocked: true, status: "RECEIVED" });
      }
      return json({ unlocked: false, status: row.status || "PENDING" });
    }

    return json({ error: "Ação inválida." }, 400);
  } catch (error) {
    console.error("asaas-payments:", error);
    return json({ error: error instanceof Error ? error.message : "Erro interno ao processar pagamento." }, 500);
  }
});
