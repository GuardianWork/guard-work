const $ = (id) => document.getElementById(id);
let accessToken = '', refreshToken = '', registration, state, company;
const parameters = new URLSearchParams(location.hash.slice(1));
const emailSecret = parameters.get('emailSecret');
const memberEmailSecret = parameters.get('memberEmailSecret');
if (location.hash) history.replaceState(null, '', location.pathname);
if (parameters.get('registration')) {
  registration = { registrationId: parameters.get('registration'), continuationToken: sessionStorage.getItem(`employer-${parameters.get('registration')}`) || '' };
  $('confirm').hidden = false;
  $('confirm-form').elements.continuationToken.value = registration.continuationToken;
}

function message(text, error = false) { $('message').textContent = text; $('message').classList.toggle('error', error); }
function values(form) { return Object.fromEntries(new FormData(form)); }
async function api(path, options = {}) {
  const headers = { ...options.headers };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (options.body && !(options.body instanceof FormData)) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(options.body); }
  const response = await fetch(path, { ...options, headers, cache: 'no-store' });
  const result = await response.json();
  if (!response.ok) {
    if (response.status === 401) { accessToken = ''; refreshToken = ''; $('anonymous').hidden = false; $('settings').hidden = true; $('admin').hidden = true; }
    if ((result.message || '').includes('DUPLICATE_TAX_CODE')) $('support').hidden = false;
    const errors = { 400: 'Thông tin hoặc mã xác thực không hợp lệ, đã hết hạn hoặc đã dùng hết số lần thử.', 401: 'Vui lòng kiểm tra thông tin đăng nhập hoặc đăng nhập lại.', 403: 'Bạn cần hoàn tất bước xác minh trước khi sử dụng chức năng này.', 404: 'Không tìm thấy hồ sơ hoặc bạn không có quyền truy cập.', 409: 'Thông tin đã tồn tại hoặc đã được cập nhật. Vui lòng tải lại và kiểm tra.', 429: 'Bạn thao tác quá nhanh. Vui lòng chờ trước khi thử lại.', 503: 'Dịch vụ gửi xác thực hiện chưa khả dụng. Vui lòng thử lại sau.' };
    throw new Error(errors[response.status] || 'Chưa thể hoàn tất thao tác. Vui lòng thử lại.');
  }
  return result.data;
}
function action(id, handler) {
  $(id).addEventListener('submit', async (event) => {
    event.preventDefault(); const button = event.target.querySelector('button[type=submit],button'); button.disabled = true;
    try { await handler(values(event.target), event.target); } catch (error) { message(error.message, true); } finally { button.disabled = false; }
  });
}
function click(id, handler) { $(id).addEventListener('click', async () => { $(id).disabled = true; try { await handler(); } catch (error) { message(error.message, true); } finally { $(id).disabled = false; } }); }

action('registration-form', async (body, form) => {
  if (body.password !== body.passwordConfirmation) throw new Error('Hai mật khẩu chưa khớp.');
  registration = await api('/api/employer-registrations', { method: 'POST', body });
  sessionStorage.setItem(`employer-${registration.registrationId}`, registration.continuationToken);
  $('registration-result').hidden = false;
  $('registration-instructions').textContent = `Giữ mã tiếp tục để xác nhận trên thiết bị khác: ${registration.continuationToken}`;
  form.elements.password.value = ''; form.elements.passwordConfirmation.value = '';
  message(registration.deliveryAccepted ? 'Đã gửi email. Hãy mở liên kết và xác nhận để tiếp tục.' : 'Thông tin đã lưu nhưng chưa gửi được email. Bạn có thể gửi lại sau 60 giây.', !registration.deliveryAccepted);
});
click('resend-email', async () => {
  const result = await api(`/api/employer-registrations/${registration.registrationId}/email/resend`, { method: 'POST', body: { continuationToken: registration.continuationToken } });
  message(result.deliveryAccepted ? 'Đã gửi lại email.' : 'Chưa gửi được email. Vui lòng thử lại sau.', !result.deliveryAccepted);
});
action('confirm-form', async (body, form) => {
  await api(`/api/employer-registrations/${registration.registrationId}/email/verify`, { method: 'POST', body: { ...body, emailSecret } });
  sessionStorage.removeItem(`employer-${registration.registrationId}`); form.reset(); $('confirm').hidden = true;
  message('Email đã xác thực. Hãy đăng nhập để xác thực số điện thoại.');
});
action('login-form', async (body, form) => {
  const tokens = await api('/api/auth/login', { method: 'POST', body });
  accessToken = tokens.accessToken; refreshToken = tokens.refreshToken; form.reset();
  $('anonymous').hidden = true; $('logout').hidden = false;
  const user = await api('/api/auth/me');
  if (user.role === 'ADMIN') { $('admin').hidden = false; await loadQueue(); }
  else { $('settings').hidden = false; await loadState(); }
});
click('logout', async () => { try { await api('/api/auth/logout', { method: 'POST', body: { refreshToken } }); } finally { accessToken = ''; refreshToken = ''; location.assign('/'); } });

async function loadState() {
  state = await api('/api/me/employer-onboarding');
  $('greeting').textContent = state.fullName;
  $('step-email').classList.toggle('done', state.emailVerified); $('step-phone').classList.toggle('done', state.phoneVerified);
  $('step-company').classList.toggle('done', state.stage === 'VERIFIED');
  const stages = { EMAIL_REQUIRED: 'Xác thực email để tiếp tục.', PHONE_REQUIRED: 'Email đã xác thực. Hãy xác thực số điện thoại.', LEGAL_REQUIRED: 'Số điện thoại đã xác thực. Hãy hoàn thiện hồ sơ công ty.', PENDING: 'Hồ sơ đang chờ quản trị viên kiểm tra. Chức năng tuyển dụng đang khóa.', REJECTED: 'Hồ sơ cần bổ sung. Xem lý do bên dưới và gửi lại.', VERIFIED: 'Công ty đã được xác minh. Bạn có thể sử dụng chức năng tuyển dụng theo quyền được cấp.' };
  $('stage').textContent = stages[state.stage] || state.stage;
  $('member-email').hidden = state.emailVerified; $('member-email-confirm').hidden = !memberEmailSecret;
  $('phone-section').hidden = !state.emailVerified || state.phoneVerified;
  $('phone-form').elements.phone.value = state.phone || '';
  const owner = state.companyRole === 'OWNER';
  $('legal-section').hidden = !state.phoneVerified || !((state.applicant && state.stage === 'LEGAL_REQUIRED') || (owner && state.stage === 'REJECTED'));
  const form = $('legal-form'); form.elements.companyName.value = state.companyName || ''; form.elements.workAddress.value = state.workAddress || '';
  if (['PENDING', 'REJECTED', 'VERIFIED'].includes(state.stage) && owner) {
    company = await api('/api/companies/me/verification'); form.elements.companyName.value = company.name; form.elements.taxCode.value = company.taxCode; form.elements.taxCode.readOnly = true;
    const attempts = await api('/api/companies/me/verification/history'); renderHistory($('history'), attempts);
    if (company.rejectionReason && !attempts.length) text($('history'), 'p', company.rejectionReason);
    const latest = attempts.at(-1); if (latest) { form.elements.workAddress.value = latest.workAddress; form.elements.submitterPosition.value = latest.submitterPosition; form.elements.representation.value = latest.representation; }
  }
}
click('member-email-send', async () => { const result = await api('/api/me/employer-onboarding/email/send', { method: 'POST' }); message(result.deliveryAccepted ? 'Đã gửi email xác thực.' : 'Chưa gửi được email.', !result.deliveryAccepted); });
click('member-email-confirm', async () => { await api('/api/me/employer-onboarding/email/verify', { method: 'POST', body: { emailSecret: memberEmailSecret } }); await loadState(); message('Đã xác thực email.'); });
action('phone-form', async (body) => { await api('/api/me/employer-onboarding/phone/send', { method: 'POST', body }); message('Mã OTP đã được gửi.'); });
action('otp-form', async (body, form) => { await api('/api/me/employer-onboarding/phone/verify', { method: 'POST', body }); form.reset(); await loadState(); message('Đã xác thực số điện thoại.'); });
action('legal-form', async (body, form) => {
  const upload = new FormData(); upload.append('file', form.elements.file.files[0]);
  const document = await api('/api/me/employer-onboarding/documents', { method: 'POST', body: upload });
  const legal = { workAddress: body.workAddress, submitterPosition: body.submitterPosition, representation: body.representation, declarationAccepted: form.elements.declarationAccepted.checked, documentId: document.documentId };
  if (state.stage === 'REJECTED') await api('/api/companies/me/verification', { method: 'PUT', body: { name: body.companyName, expectedVersion: company.version, legal } });
  else {
    await api('/api/me/employer-onboarding/company', { method: 'PUT', body: { companyName: body.companyName, workAddress: body.workAddress, expectedVersion: state.version } });
    state = await api('/api/me/employer-onboarding');
    await api('/api/companies/verification-request', { method: 'POST', body: { name: body.companyName, taxCode: body.taxCode, legal } });
  }
  form.elements.declarationAccepted.checked = false; form.elements.file.value = ''; await loadState(); message('Đã gửi hồ sơ. Bạn sẽ nhận kết quả qua email.');
});

function text(parent, tag, content) { const node = document.createElement(tag); node.textContent = content; parent.append(node); return node; }
async function download(id) {
  const response = await fetch(`/api/company-verification-documents/${id}`, { headers: { Authorization: `Bearer ${accessToken}` }, cache: 'no-store' });
  if (!response.ok) throw new Error('Không thể tải giấy tờ.');
  const url = URL.createObjectURL(await response.blob()); const link = document.createElement('a'); link.href = url; link.download = 'giay-phep'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function renderHistory(container, attempts) {
  container.replaceChildren();
  for (const a of attempts) {
    const card = document.createElement('div'); card.className = 'attempt'; container.append(card);
    text(card, 'h3', `${a.companyName} · ${a.outcome === 'VERIFIED' ? 'Đã xác minh' : a.outcome === 'REJECTED' ? 'Bị từ chối' : 'Đang chờ'}`);
    text(card, 'p', `${a.taxCode} · ${a.workAddress} · ${a.submitterPosition}`);
    text(card, 'p', new Intl.DateTimeFormat('vi-VN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date(a.submittedAt)));
    if (a.rejectionReason) text(card, 'p', a.rejectionReason);
    text(card, 'p', a.declarationText);
    const button = text(card, 'button', 'Tải giấy tờ đã nộp'); button.type = 'button'; button.addEventListener('click', () => download(a.documentId).catch(error => message(error.message, true)));
  }
}
async function loadQueue() {
  const page = await api(`/api/admin/companies/verifications?status=${encodeURIComponent($('queue-filter').value)}&size=100`);
  $('queue').replaceChildren();
  for (const c of page.content) {
    const card = document.createElement('article'); card.className = 'queue-item'; $('queue').append(card);
    text(card, 'h3', c.name); text(card, 'p', `${c.taxCode} · ${c.processingStatus} · ${c.reviewOutcome || 'Chờ kiểm tra'}`);
    const history = document.createElement('div'); card.append(history);
    renderHistory(history, await api(`/api/admin/companies/${c.id}/verification-history`));
    if (c.verificationStatus === 'PENDING') {
      const reason = document.createElement('textarea'); reason.placeholder = 'Lý do từ chối (10–1000 ký tự)'; reason.setAttribute('aria-label', 'Lý do từ chối'); reason.maxLength = 1000; card.append(reason);
      for (const [status, label] of [['VERIFIED', 'Phê duyệt'], ['REJECTED', 'Từ chối']]) {
        const button = text(card, 'button', label); button.type = 'button';
        button.addEventListener('click', async () => {
          if (status === 'REJECTED' && reason.value.trim().length < 10) return message('Vui lòng nhập lý do ít nhất 10 ký tự.', true);
          button.disabled = true;
          try { await api(`/api/admin/companies/${c.id}/verify`, { method: 'PUT', body: { status, expectedVersion: c.version, rejectionReason: status === 'REJECTED' ? reason.value.trim() : null } }); await loadQueue(); message('Đã ghi nhận kết quả kiểm tra.'); }
          catch (error) { message(error.message, true); button.disabled = false; }
        });
      }
    }
  }
}
click('load-queue', loadQueue);
