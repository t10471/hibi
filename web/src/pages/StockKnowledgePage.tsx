import { useEffect, useMemo, useState } from 'react';
import { addDoc, collection, deleteDoc, doc, onSnapshot, updateDoc } from 'firebase/firestore';
import { db } from '../firebase';
import { StockKnowledge } from '../types';
import { MarkdownView } from '../components/MarkdownView';
import { compressImageFile } from '../utils/imageCompress';

const DEFAULT_CATEGORIES = [
  'チャート・テクニカル',
  'ファンダメンタル',
  'リスク管理',
  'マクロ・指標',
  'その他',
];

export function StockKnowledgePage() {
  const [items, setItems] = useState<StockKnowledge[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string>('すべて');

  // Lightbox for full-size image inspection
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);

  // Add / Edit modal state
  const [showModal, setShowModal] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [category, setCategory] = useState(DEFAULT_CATEGORIES[0]);
  const [tagsText, setTagsText] = useState('');
  const [content, setContent] = useState('');
  const [images, setImages] = useState<string[]>([]);
  const [isPinned, setIsPinned] = useState(false);
  const [isCompressing, setIsCompressing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    const unsubscribe = onSnapshot(
      collection(db, 'stock_knowledge'),
      (snapshot) => {
        const list: StockKnowledge[] = snapshot.docs.map((d) => {
          const data = d.data();
          return {
            id: d.id,
            title: String(data.title || ''),
            category: String(data.category || 'その他'),
            tags: Array.isArray(data.tags) ? data.tags.map(String) : [],
            content: String(data.content || ''),
            images: Array.isArray(data.images) ? data.images.map(String) : [],
            isPinned: Boolean(data.isPinned),
            createdAt: data.createdAt ? String(data.createdAt) : '',
            updatedAt: data.updatedAt ? String(data.updatedAt) : '',
          };
        });

        list.sort((a, b) => {
          if (Boolean(a.isPinned) !== Boolean(b.isPinned)) {
            return a.isPinned ? -1 : 1;
          }
          return (b.updatedAt || b.createdAt || '').localeCompare(a.updatedAt || a.createdAt || '');
        });

        setItems(list);
        setLoading(false);
      },
      (err) => {
        console.error('Failed to load stock knowledge:', err);
        setLoading(false);
      }
    );
    return () => unsubscribe();
  }, []);

  const categories = useMemo(() => {
    const set = new Set<string>(DEFAULT_CATEGORIES);
    items.forEach((item) => {
      if (item.category) set.add(item.category);
    });
    return ['すべて', ...Array.from(set)];
  }, [items]);

  const filteredItems = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return items.filter((item) => {
      if (selectedCategory !== 'すべて' && item.category !== selectedCategory) return false;
      if (!q) return true;
      return (
        item.title.toLowerCase().includes(q) ||
        item.content.toLowerCase().includes(q) ||
        item.category.toLowerCase().includes(q) ||
        item.tags.some((t) => t.toLowerCase().includes(q))
      );
    });
  }, [items, selectedCategory, searchQuery]);

  const openAddModal = () => {
    setEditingId(null);
    setTitle('');
    setCategory(DEFAULT_CATEGORIES[0]);
    setTagsText('');
    setContent('');
    setImages([]);
    setIsPinned(false);
    setShowModal(true);
  };

  const openEditModal = (item: StockKnowledge) => {
    setEditingId(item.id || null);
    setTitle(item.title);
    setCategory(item.category || DEFAULT_CATEGORIES[0]);
    setTagsText(item.tags.join(', '));
    setContent(item.content);
    setImages(item.images || []);
    setIsPinned(Boolean(item.isPinned));
    setShowModal(true);
  };

  const processFiles = async (files: FileList | File[]) => {
    const imageFiles = Array.from(files).filter((f) => f.type.startsWith('image/'));
    if (imageFiles.length === 0) return;

    setIsCompressing(true);
    try {
      const compressed: string[] = [];
      for (const file of imageFiles) {
        const dataUrl = await compressImageFile(file);
        compressed.push(dataUrl);
      }
      setImages((prev) => {
        const next = [...prev, ...compressed];
        const totalChars = next.reduce((sum, s) => sum + s.length, 0);
        if (totalChars > 880_000) {
          alert('1件あたりの合計画像サイズ上限（約850KB）を超えるため、一部追加できませんでした。別の記事に分けて登録してください。');
          return prev;
        }
        return next;
      });
    } catch (err: any) {
      alert(err.message || '画像処理に失敗しました');
    } finally {
      setIsCompressing(false);
    }
  };

  const handlePaste = (e: React.ClipboardEvent) => {
    const files: File[] = [];
    const itemsList = e.clipboardData?.items;
    if (!itemsList) return;
    for (let i = 0; i < itemsList.length; i++) {
      const item = itemsList[i];
      if (item.type.startsWith('image/')) {
        const f = item.getAsFile();
        if (f) files.push(f);
      }
    }
    if (files.length > 0) {
      e.preventDefault();
      void processFiles(files);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      alert('タイトルを入力してください。');
      return;
    }

    setIsSaving(true);
    try {
      const parsedTags = tagsText
        .split(/[,、\s]+/)
        .map((t) => t.trim())
        .filter((t) => t.length > 0);
      const now = new Date().toISOString();
      const payload = {
        title: trimmedTitle,
        category: category.trim() || 'その他',
        tags: parsedTags,
        content: content.trim(),
        images,
        isPinned,
        updatedAt: now,
      };

      if (editingId) {
        await updateDoc(doc(db, 'stock_knowledge', editingId), payload);
      } else {
        await addDoc(collection(db, 'stock_knowledge'), {
          ...payload,
          createdAt: now,
        });
      }
      setShowModal(false);
    } catch (err: any) {
      alert(`保存に失敗しました: ${err.message}`);
    } finally {
      setIsSaving(false);
    }
  };

  const handleTogglePin = async (item: StockKnowledge) => {
    if (!item.id) return;
    try {
      await updateDoc(doc(db, 'stock_knowledge', item.id), {
        isPinned: !item.isPinned,
        updatedAt: new Date().toISOString(),
      });
    } catch (err: any) {
      alert(`ピン留め更新に失敗しました: ${err.message}`);
    }
  };

  const handleDelete = async (item: StockKnowledge) => {
    if (!item.id) return;
    if (!window.confirm(`「${item.title}」を削除してもよろしいですか？`)) return;
    try {
      await deleteDoc(doc(db, 'stock_knowledge', item.id));
    } catch (err: any) {
      alert(`削除に失敗しました: ${err.message}`);
    }
  };

  return (
    <div>
      {/* ヘッダー */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 16,
          flexWrap: 'wrap',
          gap: 12,
        }}
      >
        <div>
          <h2 style={{ margin: '0 0 4px 0', fontSize: 20, display: 'flex', alignItems: 'center', gap: 8 }}>
            <span>📚</span> 投資ナレッジ・図解メモ
          </h2>
          <p style={{ margin: 0, fontSize: 13, color: '#64748b' }}>
            チャートパターンや投資ルールを図解画像付きでストック（スマホアプリとリアルタイム同期）
          </p>
        </div>
        <button
          onClick={openAddModal}
          style={{
            background: '#2563eb',
            color: 'white',
            border: 'none',
            borderRadius: 6,
            padding: '8px 16px',
            fontWeight: 'bold',
            fontSize: 14,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            boxShadow: '0 2px 4px rgba(37,99,235,0.2)',
          }}
        >
          <span>＋</span> ナレッジを追加
        </button>
      </div>

      {/* 検索 & カテゴリフィルタ */}
      <div
        style={{
          background: 'white',
          borderRadius: 10,
          border: '1px solid #e2e8f0',
          padding: 14,
          marginBottom: 20,
          display: 'grid',
          gap: 10,
        }}
      >
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="🔍 タイトル・メモ・タグで検索（例: セリクラ, RSI, 出来高）"
          style={{
            width: '100%',
            padding: '8px 12px',
            borderRadius: 6,
            border: '1px solid #cbd5e1',
            fontSize: 14,
            boxSizing: 'border-box',
          }}
        />
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {categories.map((cat) => {
            const active = cat === selectedCategory;
            return (
              <button
                key={cat}
                onClick={() => setSelectedCategory(cat)}
                style={{
                  background: active ? '#2563eb' : '#f1f5f9',
                  color: active ? 'white' : '#334155',
                  border: 'none',
                  borderRadius: 999,
                  padding: '5px 12px',
                  fontSize: 12,
                  fontWeight: active ? 'bold' : 500,
                  cursor: 'pointer',
                }}
              >
                {cat}
              </button>
            );
          })}
        </div>
      </div>

      {/* 一覧 */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#64748b' }}>読み込み中...</div>
      ) : filteredItems.length === 0 ? (
        <div
          style={{
            textAlign: 'center',
            padding: '48px 20px',
            background: 'white',
            borderRadius: 12,
            border: '1px dashed #cbd5e1',
            color: '#64748b',
          }}
        >
          <div style={{ fontSize: 36, marginBottom: 10 }}>📚</div>
          <div style={{ fontWeight: 'bold', fontSize: 15, color: '#334155', marginBottom: 6 }}>
            {items.length === 0 ? 'まだ投資ナレッジがありません' : '条件に一致するナレッジが見つかりません'}
          </div>
          <p style={{ margin: 0, fontSize: 13 }}>
            右上の「＋ ナレッジを追加」から画像や解説メモ（Ctrl+Vで画像貼り付けも可）を登録できます。
          </p>
        </div>
      ) : (
        <div style={{ display: 'grid', gap: 18 }}>
          {filteredItems.map((item) => (
            <div
              key={item.id}
              style={{
                background: 'white',
                borderRadius: 12,
                border: item.isPinned ? '2px solid #93c5fd' : '1px solid #e2e8f0',
                padding: 20,
                boxShadow: '0 2px 6px rgba(0,0,0,0.04)',
              }}
            >
              {/* タイトル行 */}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'flex-start',
                  gap: 12,
                  marginBottom: 10,
                }}
              >
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 4 }}>
                    {item.isPinned && (
                      <span
                        style={{
                          fontSize: 11,
                          background: '#dbeafe',
                          color: '#1d4ed8',
                          padding: '2px 7px',
                          borderRadius: 4,
                          fontWeight: 'bold',
                        }}
                      >
                        📌 固定
                      </span>
                    )}
                    <span
                      style={{
                        fontSize: 11,
                        background: '#f1f5f9',
                        color: '#475569',
                        padding: '2px 8px',
                        borderRadius: 4,
                        fontWeight: 600,
                      }}
                    >
                      {item.category}
                    </span>
                  </div>
                  <h3 style={{ margin: 0, fontSize: 18, color: '#0f172a' }}>{item.title}</h3>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                  <button
                    onClick={() => handleTogglePin(item)}
                    title={item.isPinned ? '固定解除' : '上部に固定'}
                    style={{
                      background: item.isPinned ? '#eff6ff' : '#f8fafc',
                      border: '1px solid #cbd5e1',
                      borderRadius: 6,
                      padding: '5px 8px',
                      cursor: 'pointer',
                      fontSize: 12,
                    }}
                  >
                    📌
                  </button>
                  <button
                    onClick={() => openEditModal(item)}
                    style={{
                      background: '#f8fafc',
                      border: '1px solid #cbd5e1',
                      borderRadius: 6,
                      padding: '5px 10px',
                      cursor: 'pointer',
                      fontSize: 12,
                      color: '#334155',
                    }}
                  >
                    ✏️ 編集
                  </button>
                  <button
                    onClick={() => handleDelete(item)}
                    style={{
                      background: '#fef2f2',
                      border: '1px solid #fecaca',
                      borderRadius: 6,
                      padding: '5px 10px',
                      cursor: 'pointer',
                      fontSize: 12,
                      color: '#dc2626',
                    }}
                  >
                    削除
                  </button>
                </div>
              </div>

              {/* タグ */}
              {item.tags.length > 0 && (
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
                  {item.tags.map((tag) => (
                    <span
                      key={tag}
                      onClick={() => setSearchQuery(tag)}
                      style={{
                        fontSize: 11,
                        background: '#eff6ff',
                        color: '#2563eb',
                        padding: '2px 8px',
                        borderRadius: 999,
                        cursor: 'pointer',
                      }}
                    >
                      #{tag}
                    </span>
                  ))}
                </div>
              )}

              {/* 画像ギャラリー */}
              {item.images.length > 0 && (
                <div
                  style={{
                    display: 'grid',
                    gridTemplateColumns: item.images.length === 1 ? '1fr' : 'repeat(auto-fit, minmax(260px, 1fr))',
                    gap: 12,
                    marginBottom: item.content ? 14 : 0,
                  }}
                >
                  {item.images.map((src, idx) => (
                    <div
                      key={idx}
                      onClick={() => setLightboxSrc(src)}
                      style={{
                        borderRadius: 8,
                        overflow: 'hidden',
                        border: '1px solid #e2e8f0',
                        cursor: 'zoom-in',
                        background: '#0f172a',
                      }}
                      title="クリックで拡大表示"
                    >
                      <img
                        src={src}
                        alt={`${item.title} 図解${idx + 1}`}
                        style={{
                          width: '100%',
                          maxHeight: 460,
                          objectFit: 'contain',
                          display: 'block',
                        }}
                      />
                    </div>
                  ))}
                </div>
              )}

              {/* Markdown 解説 */}
              {item.content && (
                <div
                  style={{
                    background: '#f8fafc',
                    borderRadius: 8,
                    padding: '12px 16px',
                    border: '1px solid #e2e8f0',
                    fontSize: 14,
                    lineHeight: 1.65,
                    color: '#1e293b',
                  }}
                >
                  <MarkdownView content={item.content} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {/* 画像拡大ライトボックス */}
      {lightboxSrc && (
        <div
          onClick={() => setLightboxSrc(null)}
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.85)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1100,
            padding: 20,
            cursor: 'zoom-out',
          }}
        >
          <img
            src={lightboxSrc}
            alt="拡大図解"
            style={{
              maxWidth: '95vw',
              maxHeight: '92vh',
              objectFit: 'contain',
              borderRadius: 8,
            }}
          />
        </div>
      )}

      {/* 追加・編集モーダル */}
      {showModal && (
        <div
          onClick={() => setShowModal(false)}
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(15, 23, 42, 0.55)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: 16,
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            onPaste={handlePaste}
            style={{
              background: 'white',
              borderRadius: 12,
              padding: 24,
              width: '100%',
              maxWidth: 620,
              maxHeight: '90vh',
              overflowY: 'auto',
              boxShadow: '0 20px 25px -5px rgba(0, 0, 0, 0.2)',
            }}
          >
            <h3 style={{ margin: '0 0 16px 0', fontSize: 18 }}>
              {editingId ? '投資ナレッジを編集' : '新しい投資ナレッジを追加'}
            </h3>
            <form onSubmit={handleSave} style={{ display: 'grid', gap: 14 }}>
              <div>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 4 }}>
                  タイトル *
                </label>
                <input
                  type="text"
                  required
                  placeholder="例: セリングクライマックスの見極め方"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '8px 12px',
                    borderRadius: 6,
                    border: '1px solid #cbd5e1',
                    fontSize: 14,
                    boxSizing: 'border-box',
                  }}
                />
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <div>
                  <label style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 4 }}>
                    カテゴリ
                  </label>
                  <select
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    style={{
                      width: '100%',
                      padding: '8px 10px',
                      borderRadius: 6,
                      border: '1px solid #cbd5e1',
                      fontSize: 14,
                    }}
                  >
                    {DEFAULT_CATEGORIES.map((c) => (
                      <option key={c} value={c}>
                        {c}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 4 }}>
                    タグ（カンマ・スペース区切り）
                  </label>
                  <input
                    type="text"
                    placeholder="例: セリクラ, 出来高, 底打ち"
                    value={tagsText}
                    onChange={(e) => setTagsText(e.target.value)}
                    style={{
                      width: '100%',
                      padding: '8px 10px',
                      borderRadius: 6,
                      border: '1px solid #cbd5e1',
                      fontSize: 14,
                      boxSizing: 'border-box',
                    }}
                  />
                </div>
              </div>

              {/* 画像アップロード（選択 / D&D / ペースト） */}
              <div>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 4 }}>
                  図解・チャート画像（ファイル選択 / ドラッグ&ドロップ / Ctrl+Vで貼付可）
                </label>
                <div
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => {
                    e.preventDefault();
                    if (e.dataTransfer.files) void processFiles(e.dataTransfer.files);
                  }}
                  style={{
                    border: '2px dashed #93c5fd',
                    borderRadius: 8,
                    padding: 14,
                    textAlign: 'center',
                    background: '#eff6ff',
                  }}
                >
                  <input
                    type="file"
                    accept="image/*"
                    multiple
                    onChange={(e) => {
                      if (e.target.files) void processFiles(e.target.files);
                    }}
                    style={{ fontSize: 13 }}
                  />
                  <div style={{ fontSize: 11, color: '#475569', marginTop: 6 }}>
                    {isCompressing ? '画像を最適化中...' : '自動で圧縮してスマホと同期します（Ctrl+V / Cmd+V でも貼り付け可能）'}
                  </div>
                </div>

                {images.length > 0 && (
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 10 }}>
                    {images.map((src, idx) => (
                      <div
                        key={idx}
                        style={{
                          position: 'relative',
                          width: 110,
                          height: 75,
                          borderRadius: 6,
                          overflow: 'hidden',
                          border: '1px solid #cbd5e1',
                        }}
                      >
                        <img
                          src={src}
                          alt={`添付${idx + 1}`}
                          style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        />
                        <button
                          type="button"
                          onClick={() => setImages((prev) => prev.filter((_, i) => i !== idx))}
                          style={{
                            position: 'absolute',
                            top: 2,
                            right: 2,
                            background: 'rgba(0,0,0,0.7)',
                            color: 'white',
                            border: 'none',
                            borderRadius: 999,
                            width: 20,
                            height: 20,
                            fontSize: 11,
                            cursor: 'pointer',
                          }}
                        >
                          ✕
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div>
                <label style={{ display: 'block', fontSize: 13, fontWeight: 600, marginBottom: 4 }}>
                  解説・メモ（Markdown対応）
                </label>
                <textarea
                  rows={6}
                  placeholder="- 確認するサインやエントリー条件のメモ..."
                  value={content}
                  onChange={(e) => setContent(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '8px 12px',
                    borderRadius: 6,
                    border: '1px solid #cbd5e1',
                    fontSize: 14,
                    fontFamily: 'inherit',
                    boxSizing: 'border-box',
                  }}
                />
              </div>

              <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={isPinned}
                  onChange={(e) => setIsPinned(e.target.checked)}
                />
                <span>📌 一覧の上部に固定する</span>
              </label>

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 6 }}>
                <button
                  type="button"
                  onClick={() => setShowModal(false)}
                  style={{
                    background: '#f1f5f9',
                    border: 'none',
                    borderRadius: 6,
                    padding: '8px 16px',
                    color: '#475569',
                    fontWeight: 500,
                    cursor: 'pointer',
                  }}
                >
                  キャンセル
                </button>
                <button
                  type="submit"
                  disabled={isSaving || isCompressing}
                  style={{
                    background: '#2563eb',
                    border: 'none',
                    borderRadius: 6,
                    padding: '8px 20px',
                    color: 'white',
                    fontWeight: 'bold',
                    cursor: isSaving ? 'wait' : 'pointer',
                  }}
                >
                  {isSaving ? '保存中...' : '保存する'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
