(()=>{const dialog=document.getElementById('titleDialog'),form=document.getElementById('titleForm'),input=document.getElementById('appTitleInput'),error=document.getElementById('titleError'),save=document.getElementById('titleSave');if(!dialog)return;document.getElementById('appTitleEdit').addEventListener('click',()=>dialog.showModal());document.getElementById('titleCancel').addEventListener('click',()=>dialog.close());form.addEventListener('submit',async e=>{e.preventDefault();if(!form.reportValidity())return;save.disabled=true;error.style.display='none';try{const response=await fetch('/settings/title',{method:'POST',body:new URLSearchParams(new FormData(form)),headers:{'X-Requested-With':'XMLHttpRequest'}});if(response.ok){window.location.reload();return}error.textContent=await response.text();error.style.display='block'}catch(ex){error.textContent='\u4fdd\u5b58\u3067\u304d\u307e\u305b\u3093\u3067\u3057\u305f';error.style.display='block'}finally{save.disabled=false}})})();

(() => {
  const modal = document.getElementById('wishModal');
  if (!modal) return;
  const form = document.getElementById('wishForm');
  const title = document.getElementById('wishTitle');
  const category = document.getElementById('wishCategory');
  const due = document.getElementById('wishDueDate');
  const budget = document.getElementById('wishBudget');
  const locationName = document.getElementById('wishLocationName');
  const address = document.getElementById('wishAddress');
  const latitude = document.getElementById('wishLatitude');
  const longitude = document.getElementById('wishLongitude');
  const memo = document.getElementById('wishMemo');
  const priorityGroup = modal.querySelector('.priority-stars');
  const image = document.getElementById('wishImage');
  const preview = document.getElementById('wishPreview');
  const id = document.getElementById('wishId');
  const date = document.getElementById('wishCompletedDate');
  const amount = document.getElementById('wishCompletedAmount');
  const completion = document.getElementById('completionFields');
  const error = document.getElementById('wishError');
  const save = document.getElementById('wishSave');
  const remove = document.getElementById('removeImage');
  const removeRow = document.getElementById('removeImageRow');
  let originalDate = '', imageUrl = '', hasCustomImage = false, objectUrl = '';

  const categoryBg = () => category.selectedOptions[0]?.dataset.bg || '';
  const showImage = url => {
    preview.style.backgroundImage = url ? `url('${url}')` : 'linear-gradient(145deg,#ffca9a,#a9d9e8 48%,#d4b8e7)';
  };
  const clearPreview = () => {
    if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = ''; }
  };
  const setPriority = value => {
    const selected = Math.min(5, Math.max(1, Number(value) || 3));
    const radio = priorityGroup.querySelector(`input[value="${selected}"]`);
    if (radio) radio.checked = true;
    priorityGroup.querySelectorAll('.priority-star').forEach(star => {
      star.classList.toggle('selected', Number(star.dataset.value) <= selected);
    });
  };
  const resetModal = () => {
    form.reset();
    id.value = '';
    completion.hidden = true;
    removeRow.hidden = true;
    remove.checked = false;
    amount.disabled = true;
    originalDate = '';
    imageUrl = '';
    hasCustomImage = false;
    error.style.display = 'none';
    error.textContent = '';
    clearPreview();
    setPriority(3);
    showImage(categoryBg());
  };
  const close = () => { modal.close(); resetModal(); };

  document.getElementById('newWishButton').addEventListener('click', () => {
    resetModal();
    document.getElementById('wishModalTitle').textContent = '新規追加';
    modal.showModal();
  });
  document.addEventListener('click', event => {
    const button = event.target.closest('[data-edit-id]');
    if (!button) return;
    resetModal();
    document.getElementById('wishModalTitle').textContent = '項目を編集';
    id.value = button.dataset.editId;
    title.value = button.dataset.title;
    category.value = button.dataset.categoryId;
    due.value = button.dataset.dueDate;
    budget.value = button.dataset.budget;
    locationName.value = button.dataset.locationName || '';
    address.value = button.dataset.address || '';
    latitude.value = button.dataset.latitude || '';
    longitude.value = button.dataset.longitude || '';
    memo.value = button.dataset.memo || '';
    setPriority(button.dataset.priority);
    date.value = button.dataset.completedDate;
    amount.value = button.dataset.completedAmount;
    originalDate = date.value;
    completion.hidden = false;
    amount.disabled = !date.value;
    imageUrl = button.dataset.imageUrl || '';
    hasCustomImage = button.dataset.customImage === 'true';
    removeRow.hidden = !hasCustomImage;
    showImage(imageUrl || categoryBg());
    modal.showModal();
  });
  priorityGroup.addEventListener('change', event => {
    if (event.target.matches('input[name="priority"]')) setPriority(event.target.value);
  });
  category.addEventListener('change', () => {
    if ((!image.files.length && !hasCustomImage) || remove.checked) showImage(categoryBg());
  });
  image.addEventListener('change', () => {
    clearPreview();
    if (image.files[0]) { objectUrl = URL.createObjectURL(image.files[0]); showImage(objectUrl); }
    else showImage(remove.checked ? categoryBg() : imageUrl || categoryBg());
  });
  remove.addEventListener('change', () => showImage(remove.checked ? categoryBg() : imageUrl || categoryBg()));
  date.addEventListener('change', () => {
    if (originalDate && !date.value && !confirm('達成日を削除すると実際の金額もクリアされます。続けますか？')) {
      date.value = originalDate;
      return;
    }
    amount.disabled = !date.value;
    if (!date.value) amount.value = '';
  });
  document.getElementById('wishClose').addEventListener('click', close);
  document.getElementById('wishCancel').addEventListener('click', close);
  modal.addEventListener('click', event => { if (event.target === modal) close(); });
  modal.addEventListener('cancel', () => resetModal());
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (!form.reportValidity()) return;
    error.style.display = 'none';
    save.disabled = true;
    try {
      const response = await fetch('/save', {
        method: 'POST', body: new FormData(form), headers: { 'X-Requested-With': 'XMLHttpRequest' }
      });
      if (response.ok) { window.location.href = '/'; return; }
      error.textContent = await response.text() || '保存できませんでした';
      error.style.display = 'block';
    } catch (exception) {
      error.textContent = '通信に失敗しました。入力内容を確認して再度お試しください';
      error.style.display = 'block';
    } finally { save.disabled = false; }
  });
})();