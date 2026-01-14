public class Expense {
    int amount = -1;
    Category category;
    String date;
    String description;

    public Expense(int amount, Category category, String date, String description) {
        this.amount = amount;
        this.category = category;
        this.date = date;
        this.description = description;
    }


}
