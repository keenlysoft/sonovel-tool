(() => {
  const activeDownloads = new Set();
  const downloadQueue = [];
  const POLL_INTERVAL_MS = 1000;
  const MAX_POLL_ATTEMPTS = 60;
  const BOOK_FETCH_TIMEOUT_MS = 30 * 60 * 1000;
  const LOCAL_BOOKS_TIMEOUT_MS = 15 * 1000;
  const BOOK_FETCH_LOCK_NAME = 'sonovel-book-fetch';
  let isProcessingQueue = false;

  const delayWithSignal = (milliseconds, signal) => new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const timeoutId = window.setTimeout(() => {
      signal.removeEventListener('abort', handleAbort);
      resolve();
    }, milliseconds);
    const handleAbort = () => {
      window.clearTimeout(timeoutId);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    signal.addEventListener('abort', handleAbort, { once: true });
  });

  const buildFilename = (book) => {
    const author = book.author.trim();
    return `${book.bookName.trim()}${author ? `(${author})` : ''}.txt`;
  };

  const normalizeFilename = (filename) => filename.trim().toLowerCase();

  const localBookSignature = (book) => (
    `${String(book?.size ?? '')}:${String(book?.timestamp ?? book?.downloadedAt ?? '')}`
  );

  const selectDownloadedBook = (book, booksBeforeDownload, booksAfterDownload) => {
    const title = normalizeFilename(book.bookName);
    const expected = normalizeFilename(buildFilename(book));
    const hasAuthor = Boolean(book.author.trim());
    const previous = new Map(booksBeforeDownload.map((file) => [
      normalizeFilename(String(file.name || file.filename || '')),
      localBookSignature(file)
    ]));
    const candidates = booksAfterDownload.filter((file) => {
      const filename = normalizeFilename(String(file.name || file.filename || ''));
      let matches = filename === expected;
      if (!hasAuthor && title && filename.startsWith(`${title}(`) && filename.endsWith(').txt')) {
        const inferredAuthor = filename.slice(title.length + 1, -5).trim();
        matches = Boolean(inferredAuthor);
      }
      return matches && previous.get(filename) !== localBookSignature(file);
    });
    if (candidates.length > 1) {
      throw new Error('下载后发现多个可能的 TXT 文件，请在“已下书籍”中核对');
    }
    return candidates[0] || null;
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { selectDownloadedBook };
    return;
  }

  const showNotice = (message, kind = 'info') => {
    let container = document.getElementById('async-download-notices');
    if (!container) {
      container = document.createElement('div');
      container.id = 'async-download-notices';
      container.setAttribute('aria-live', 'polite');
      Object.assign(container.style, {
        position: 'fixed',
        top: '16px',
        right: '16px',
        zIndex: '10000',
        display: 'grid',
        gap: '8px',
        maxWidth: 'min(420px, calc(100vw - 32px))',
        pointerEvents: 'none'
      });
      document.body.appendChild(container);
    }

    const notice = document.createElement('div');
    notice.textContent = message;
    Object.assign(notice.style, {
      padding: '10px 14px',
      borderRadius: '8px',
      color: '#fff',
      background: kind === 'error' ? '#b42318' : '#344054',
      boxShadow: '0 6px 18px rgba(0, 0, 0, 0.2)'
    });
    container.appendChild(notice);
    window.setTimeout(() => notice.remove(), 5000);
  };

  const formatSize = (bytes) => {
    if (!bytes) {
      return '0.00 B';
    }
    const units = ['B', 'KB', 'MB', 'GB'];
    const unitIndex = Math.min(
      Math.floor(Math.log(bytes) / Math.log(1024)),
      units.length - 1
    );
    return `${(bytes / (1024 ** unitIndex)).toFixed(2)} ${units[unitIndex]}`;
  };

  const formatDate = (timestamp) => new Date(timestamp).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });

  const renderLocalBooks = (books) => {
    const tableBody = document.getElementById('tableBody');
    if (!tableBody) {
      return;
    }

    const fragment = document.createDocumentFragment();
    books.forEach((book) => {
      const filename = String(book.name || book.filename || '');
      const row = document.createElement('tr');
      const values = [
        ['文件名', filename],
        ['文件大小', formatSize(Number(book.size) || 0)],
        ['下载时间', formatDate(book.timestamp)]
      ];
      values.forEach(([label, value]) => {
        const cell = document.createElement('td');
        cell.dataset.label = label;
        cell.textContent = value;
        row.appendChild(cell);
      });

      const actionCell = document.createElement('td');
      actionCell.dataset.label = '操作';
      const downloadButton = document.createElement('button');
      downloadButton.className = 'btn btn-secondary btn-download';
      downloadButton.dataset.filename = filename;
      downloadButton.textContent = '下载';
      actionCell.appendChild(downloadButton);
      row.appendChild(actionCell);
      fragment.appendChild(row);
    });
    tableBody.replaceChildren(fragment);
  };

  const getLocalBooks = async (signal) => {
    const response = await fetch('/local-books', { signal });
    if (!response.ok) {
      throw new Error(`刷新下载列表失败 (${response.status})`);
    }
    const payload = await response.json();
    return Array.isArray(payload?.data) ? payload.data : [];
  };

  const getLocalBooksWithTimeout = async () => {
    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), LOCAL_BOOKS_TIMEOUT_MS);
    try {
      return await getLocalBooks(controller.signal);
    } catch (error) {
      if (controller.signal.aborted) {
        throw new Error('刷新下载列表超时');
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
    }
  };

  const refreshLocalBooks = async () => {
    const books = await getLocalBooksWithTimeout();
    renderLocalBooks(books);
    return books;
  };

  const readBookFromRow = (button) => {
    const row = button.closest('tr');
    const cells = row?.querySelectorAll('td');
    const sourceLink = row?.querySelector('a[href]');
    if (!cells || cells.length < 6 || !sourceLink) {
      throw new Error('无法读取书籍信息，请重新搜索');
    }

    return {
      bookName: cells[1].textContent?.trim() || '',
      author: cells[2].textContent?.trim() || '',
      latestChapter: cells[3].textContent?.trim() || '',
      lastUpdateTime: cells[4].textContent?.trim() || '',
      sourceId: cells[5].textContent?.trim() || '',
      url: sourceLink.href
    };
  };

  const waitForDownloadedBook = async (book, booksBeforeDownload, signal) => {
    for (let attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt += 1) {
      const books = await getLocalBooks(signal);
      const downloaded = selectDownloadedBook(book, booksBeforeDownload, books);
      if (downloaded) {
        return {
          filename: String(downloaded.name || downloaded.filename),
          books
        };
      }

      await delayWithSignal(POLL_INTERVAL_MS, signal);
    }

    throw new Error('下载等待超时，请在“已下书籍”中刷新查看');
  };

  const runLockedDownload = async (task) => {
    const { book, expectedFilename, queuedSignaturePromise } = task;
    const controller = new AbortController();
    let timedOut = false;
    const timeoutId = window.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, BOOK_FETCH_TIMEOUT_MS);
    const execute = async () => {
      const queuedSignature = await queuedSignaturePromise;
      let booksBeforeDownload = await getLocalBooks(controller.signal);
      const bookBeforeDownload = booksBeforeDownload.find((candidate) => (
        normalizeFilename(String(candidate.name || candidate.filename || '')) === normalizeFilename(expectedFilename)
      ));
      const currentSignature = bookBeforeDownload ? localBookSignature(bookBeforeDownload) : null;

      // Another tab may have completed this same book while this task waited
      // for the origin-wide Web Lock. Reuse that result instead of fetching it again.
      if (book.author.trim() && queuedSignature !== undefined
          && queuedSignature !== currentSignature && bookBeforeDownload) {
        return {
          filename: String(bookBeforeDownload.name || bookBeforeDownload.filename),
          books: booksBeforeDownload
        };
      }

      const query = new URLSearchParams(book).toString();
      let response;
      do {
        response = await fetch(`/book-fetch?${query}`, { signal: controller.signal });
        if (response.status === 429) {
          await response.arrayBuffer();
          await delayWithSignal(POLL_INTERVAL_MS, controller.signal);
          booksBeforeDownload = await getLocalBooks(controller.signal);
        }
      } while (response.status === 429);
      if (!response.ok) {
        throw new Error(`启动下载失败 (${response.status})`);
      }
      await response.arrayBuffer();
      return await waitForDownloadedBook(book, booksBeforeDownload, controller.signal);
    };

    try {
      if (navigator.locks?.request) {
        return await navigator.locks.request(
          BOOK_FETCH_LOCK_NAME,
          { mode: 'exclusive', signal: controller.signal },
          execute
        );
      }
      return await execute();
    } catch (error) {
      if (timedOut) {
        throw new Error('单本书下载超过 30 分钟，已继续处理后续队列');
      }
      throw error;
    } finally {
      window.clearTimeout(timeoutId);
    }
  };

  const downloadToBrowser = (filename) => {
    const link = document.createElement('a');
    link.href = `/book-download?filename=${encodeURIComponent(filename)}`;
    link.download = filename;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    link.remove();
  };

  const runDownload = async (task) => {
    const { button, book, expectedFilename, originalText, taskKey } = task;
    button.textContent = '后台下载中…';

    try {
      const { filename, books } = await runLockedDownload(task);
      button.textContent = '已下载 ✓';
      renderLocalBooks(books);
      downloadToBrowser(filename);
      showNotice(`《${book.bookName}》下载完成`);
    } catch (error) {
      console.error('Async download failed:', error);
      button.textContent = '下载失败';
      showNotice(error instanceof Error ? error.message : '下载失败', 'error');
    } finally {
      activeDownloads.delete(taskKey);
      window.setTimeout(() => {
        button.textContent = originalText;
        button.disabled = false;
      }, 2000);
    }
  };

  const processQueue = async () => {
    if (isProcessingQueue) {
      return;
    }

    isProcessingQueue = true;
    try {
      while (downloadQueue.length > 0) {
        const task = downloadQueue.shift();
        await runDownload(task);
      }
    } finally {
      isProcessingQueue = false;
    }
  };

  const enqueueDownload = (button) => {
    const book = readBookFromRow(button);
    const expectedFilename = buildFilename(book);
    const taskKey = normalizeFilename(expectedFilename);
    if (activeDownloads.has(taskKey)) {
      showNotice('这本书已在下载队列中，请勿重复提交');
      return;
    }

    activeDownloads.add(taskKey);
    const originalText = button.textContent || '下载';
    const queuedSignaturePromise = getLocalBooksWithTimeout()
      .then((books) => {
        const existingBook = books.find((candidate) => (
          normalizeFilename(String(candidate.name || candidate.filename || '')) === taskKey
        ));
        return existingBook ? localBookSignature(existingBook) : null;
      })
      .catch(() => undefined);
    button.disabled = true;
    button.textContent = isProcessingQueue ? '排队中…' : '后台下载中…';
    downloadQueue.push({
      button,
      book,
      expectedFilename,
      originalText,
      taskKey,
      queuedSignaturePromise
    });
    void processQueue();
  };

  document.addEventListener('DOMContentLoaded', () => {
    const table = document.getElementById('downloadTableBody');
    const refreshButton = document.getElementById('refreshList');
    table?.addEventListener('click', (event) => {
      if (!(event.target instanceof Element)) {
        return;
      }
      const button = event.target.closest('button[data-index]');
      if (!button) {
        return;
      }

      event.preventDefault();
      event.stopImmediatePropagation();
      try {
        enqueueDownload(button);
      } catch (error) {
        showNotice(error instanceof Error ? error.message : '无法加入下载队列', 'error');
      }
    }, true);

    refreshButton?.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      void refreshLocalBooks().catch((error) => {
        showNotice(error instanceof Error ? error.message : '刷新失败', 'error');
      });
    }, true);
  });
})();
