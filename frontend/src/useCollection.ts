import { ref, shallowRef, onMounted } from "vue";
import { api, notifyError } from "./api";
import type { Page } from "./types";

export function useCollection<T>(path: string) {
  const rows = shallowRef<T[]>([]);
  const page = ref(1),
    total = ref(0),
    loading = ref(false),
    name = ref(""),
    status = ref("");
  async function load(reset = false) {
    if (reset) page.value = 1;
    loading.value = true;
    try {
      const query = new URLSearchParams({
        page: String(page.value),
        size: "20",
      });
      if (name.value.trim()) query.set("name", name.value.trim());
      if (status.value) query.set("status", status.value);
      const result = await api<Page<T>>(path + "?" + query);
      rows.value = result.items;
      total.value = result.total;
    } catch (e) {
      notifyError(e);
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => void load());
  return { rows, page, total, loading, name, status, load };
}
